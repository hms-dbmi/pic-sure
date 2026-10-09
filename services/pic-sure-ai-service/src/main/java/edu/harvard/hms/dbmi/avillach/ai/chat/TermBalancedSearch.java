package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
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

import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/**
 * Answers a {@code search_concepts} call that joins several terms with {@code |} by searching each term itself and interleaving the
 * results, so a term with many matches (smoking) cannot fill the page and hide another term's (alcohol use). The MCP tool ranks the
 * combined search as one list, which is what the researcher's table uses, so this is only about what the model is shown. Lives here and
 * not in {@code pic-sure-mcp} because that tool is shared with other clients.
 */
final class TermBalancedSearch {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Page size the MCP tool uses when the model sends none, and the largest it honors. */
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 50;

    /** More terms than this fall back to the plain combined search; each term is one extra gateway call. */
    static final int MAX_TERMS = 8;

    private TermBalancedSearch() {
    }

    /**
     * @param argumentsJson the model's {@code search_concepts} arguments
     * @param searchOneTerm runs {@code search_concepts} with the given arguments JSON
     * @return the merged result, or null when the call should run as it came: one term, too many terms, a later page, unreadable
     *         arguments, or any per-term search that failed
     */
    static ToolResult search(String argumentsJson, Function<String, ToolResult> searchOneTerm) {
        JsonNode arguments;
        try {
            arguments = JSON.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
        if (arguments == null || !arguments.path("query").isTextual() || arguments.path("page").asInt(0) > 0) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String term : arguments.path("query").asText().split("\\|")) {
            if (!term.isBlank()) {
                terms.add(term.trim());
            }
        }
        if (terms.size() < 2 || terms.size() > MAX_TERMS) {
            return null;
        }
        int pageSize = Math.min(MAX_PAGE_SIZE, arguments.path("pageSize").asInt(DEFAULT_PAGE_SIZE));
        if (pageSize < 1) {
            return null;
        }

        Map<String, JsonNode> perTermConcepts = new LinkedHashMap<>();
        ObjectNode termTotals = JSON.createObjectNode();
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Map<String, CompletableFuture<ToolResult>> searches = new LinkedHashMap<>();
        // The terms are independent, so they run at once and the wait is the slowest one, not the sum.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String term : terms) {
                ObjectNode termArguments = ((ObjectNode) arguments).deepCopy();
                termArguments.put("query", term).put("pageSize", pageSize).put("page", 0);
                searches.put(term, CompletableFuture.supplyAsync(() -> {
                    if (logContext != null) {
                        MDC.setContextMap(logContext);
                    }
                    try {
                        return searchOneTerm.apply(termArguments.toString());
                    } finally {
                        MDC.clear();
                    }
                }, executor));
            }
        }
        for (Map.Entry<String, CompletableFuture<ToolResult>> search : searches.entrySet()) {
            try {
                ToolResult result = search.getValue().join();
                if (result.error()) {
                    return null;
                }
                JsonNode parsed = JSON.readTree(result.content());
                if (!parsed.path("concepts").isArray() || !parsed.path("total").isInt()) {
                    return null;
                }
                perTermConcepts.put(search.getKey(), parsed.path("concepts"));
                termTotals.put(search.getKey(), parsed.path("total").asInt());
            } catch (Exception e) {
                return null;
            }
        }

        ArrayNode merged = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        List<JsonNode> lists = new ArrayList<>(perTermConcepts.values());
        for (int row = 0; merged.size() < pageSize; row++) {
            boolean any = false;
            for (JsonNode concepts : lists) {
                if (row < concepts.size() && merged.size() < pageSize) {
                    any = true;
                    String path = concepts.get(row).path("conceptPath").asText();
                    if (seen.add(path)) {
                        merged.add(concepts.get(row));
                    }
                }
            }
            if (!any) {
                break;
            }
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("page", 0);
        out.put("total", termTotals.properties().stream().mapToInt(e -> e.getValue().asInt()).sum());
        out.set("concepts", merged);
        out.set("termTotals", termTotals);
        return ToolResult.success(out.toString());
    }
}
