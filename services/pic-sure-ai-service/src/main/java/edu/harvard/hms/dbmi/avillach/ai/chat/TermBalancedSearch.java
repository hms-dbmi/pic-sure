package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/**
 * Runs a several-term {@code search_concepts} call ({@code |}-joined in {@code search}, or listed in {@code terms}) as one OR search, fetching
 * its pages in parallel up to a cap, and interleaves the results by the terms that matched them, so a term with many matches cannot fill
 * the page and hide another's. Only the model sees this; the researcher's table uses the combined ranking. Lives here, not in
 * {@code pic-sure-mcp}, because that tool is shared with other clients.
 */
final class TermBalancedSearch {

    private static final Logger log = LoggerFactory.getLogger(TermBalancedSearch.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The MCP tool's defaults. */
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 50;

    /** Most terms one call may search; {@code pic-sure-mcp} refuses a longer {@code terms} list, so a longer one is passed through for it to refuse. */
    static final int MAX_TERMS = 10;

    /** Pages fetched after the first, so one search takes at most 1 + this many dictionary calls and sees at most that many pages of matches. */
    static final int MAX_EXTRA_PAGES = 5;

    /** Added to a result that holds only the best-ranked matches, so the model does not report a term it could not see as sparse. */
    static final String TRUNCATED_NOTE = "Only the best %d of the %d matches were examined, so a term may have more matches than concepts "
        + "listed for it. Do not say a term has few or no matches because of this list. Tell the researcher the search is very broad and "
        + "ask them to narrow it, or suggest more specific terms.";

    private TermBalancedSearch() {
    }

    /** @return the trimmed, non-blank terms of a {@code |}-joined search; empty for null */
    static List<String> terms(String search) {
        if (search == null) {
            return List.of();
        }
        return Arrays.stream(search.split("\\|")).map(String::trim).filter(term -> !term.isEmpty()).toList();
    }

    /**
     * @param arguments a {@code search_concepts} call's (or a {@code propose_query} call's) arguments
     * @return its search text, with {@code terms} joined by {@code " | "}; null when it has neither {@code search} nor {@code terms}
     */
    static String searchText(JsonNode arguments) {
        if (arguments.path("search").isTextual()) {
            return arguments.path("search").asText();
        }
        if (!arguments.path("terms").isArray()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        arguments.path("terms").forEach(term -> {
            if (term.isTextual()) {
                terms.add(term.asText());
            }
        });
        return String.join(" | ", terms);
    }

    /**
     * Runs a several-term call as one OR search at the largest page size, then the rest of its pages in parallel, and gives each term an
     * equal share of the page the model sees. The pages come from the dictionary's own ranking of the terms together, where a term with
     * many matches can fill the first pages, so each page's concepts are grouped by the terms they matched and interleaved.
     *
     * @param argumentsJson a {@code search_concepts} call's arguments
     * @param searchTerms runs one {@code search_concepts} call with the given arguments
     * @return the merged result, or null when the call should run as it came
     */
    static ToolResult search(String argumentsJson, Function<String, ToolResult> searchTerms) {
        JsonNode arguments;
        try {
            arguments = JSON.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
        if (arguments == null || arguments.path("page").asInt(0) > 0) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>(terms(searchText(arguments)));
        if (terms.size() < 2 || terms.size() > MAX_TERMS) {
            return null;
        }
        int pageSize = Math.min(MAX_PAGE_SIZE, arguments.path("pageSize").asInt(DEFAULT_PAGE_SIZE));
        if (pageSize < 1) {
            return null;
        }

        JsonNode first = fetchPage(arguments, terms, 0, searchTerms);
        if (first == null || !first.path("termTotals").isObject()) {
            return null;
        }
        long total = first.path("total").asLong();
        int pages = (int) Math.min((total + MAX_PAGE_SIZE - 1) / MAX_PAGE_SIZE, 1 + MAX_EXTRA_PAGES);
        List<JsonNode> fetched = new ArrayList<>();
        fetched.add(first);
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        List<CompletableFuture<JsonNode>> rest = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int page = 1; page < pages; page++) {
                int number = page;
                rest.add(CompletableFuture.supplyAsync(() -> {
                    if (logContext != null) {
                        MDC.setContextMap(logContext);
                    }
                    try {
                        return fetchPage(arguments, terms, number, searchTerms);
                    } finally {
                        MDC.clear();
                    }
                }, executor));
            }
        }
        for (CompletableFuture<JsonNode> page : rest) {
            JsonNode parsed;
            try {
                parsed = page.join();
            } catch (Exception e) {
                return null;
            }
            if (parsed == null) {
                return null;
            }
            fetched.add(parsed);
        }

        // Grouped by the term names the dictionary reported, which are the given terms with punctuation stripped.
        Map<String, List<JsonNode>> byTerm = new LinkedHashMap<>();
        first.path("termTotals").fieldNames().forEachRemaining(term -> byTerm.put(term, new ArrayList<>()));
        boolean dropped = false;
        for (JsonNode page : fetched) {
            for (JsonNode concept : page.path("concepts")) {
                boolean placed = false;
                for (JsonNode matched : concept.path("matchedTerms")) {
                    List<JsonNode> group = byTerm.get(matched.asText());
                    if (group != null) {
                        group.add(concept);
                        placed = true;
                    }
                }
                dropped |= !placed;
            }
        }
        // A concept that fits no term group would vanish from the merged page; the plain search keeps it.
        if (dropped) {
            return null;
        }

        ArrayNode merged = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        List<List<JsonNode>> lists = new ArrayList<>(byTerm.values());
        for (int row = 0; merged.size() < pageSize; row++) {
            boolean any = false;
            for (List<JsonNode> concepts : lists) {
                if (row < concepts.size() && merged.size() < pageSize) {
                    any = true;
                    JsonNode concept = concepts.get(row);
                    if (seen.add(concept.path("conceptPath").asText())) {
                        merged.add(concept);
                    }
                }
            }
            if (!any) {
                break;
            }
        }

        log.info("Term search: terms={} total={} dictionaryCalls={} merged={}", terms.size(), total, pages, merged.size());
        ObjectNode out = JSON.createObjectNode();
        out.put("page", 0);
        out.put("total", total);
        out.set("concepts", merged);
        out.set("termTotals", first.path("termTotals"));
        long examined = (long) pages * MAX_PAGE_SIZE;
        if (total > examined) {
            out.put("note", TRUNCATED_NOTE.formatted(examined, total));
        }
        return ToolResult.success(out.toString());
    }

    /** @return the parsed page of an OR search of the terms, or null when the call failed or its answer was not a page */
    private static JsonNode fetchPage(JsonNode arguments, Set<String> terms, int page, Function<String, ToolResult> searchTerms) {
        ObjectNode call = ((ObjectNode) arguments).deepCopy();
        call.remove("search");
        ArrayNode termList = call.putArray("terms");
        terms.forEach(termList::add);
        call.put("pageSize", MAX_PAGE_SIZE).put("page", page);
        try {
            ToolResult result = searchTerms.apply(call.toString());
            if (result.error()) {
                return null;
            }
            JsonNode parsed = JSON.readTree(result.content());
            return parsed.path("concepts").isArray() && parsed.path("total").isNumber() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }
}
