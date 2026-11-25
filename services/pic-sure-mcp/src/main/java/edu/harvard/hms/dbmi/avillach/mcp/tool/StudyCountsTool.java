package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetCategory;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetFilter;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code authorized_study_counts} tool: exact participant counts per study for the signed-in user.
 *
 * <p>It leads with study consents, when the open channel will list them. The open channel's CROSS_COUNT is keyed by every concept path
 * under {@code \_studies_consents\}, so when it comes back with cells besides the root total, one exact CROSS_COUNT over those paths is the
 * answer. Many deployments return only the total (the open backend's {@code cross-count-total-only}, on by default) and others have no
 * consent concepts at all; in both the tool falls back to the dictionary, which is the route most deployments will take. The dictionary is
 * open and says nothing about who is asking, so the work that needs only it is what is cached: for each dataset, a short list of candidate
 * concepts that most participants would have, chosen by demographic terms such as sex and age. No count is cached and no count is used to
 * choose, because a count depends on the caller's consents. Each call counts the candidates as the caller and takes the largest per
 * dataset. The cache is built only when there are no study consents.
 *
 * <p>Registered as a tool specification bean by {@code QueryToolConfig}, which calls {@link #handle} through this bean so the audit aspect
 * sees every call.
 */
@Component
public class StudyCountsTool implements AuthorizedChannelTool {

    /** The tool name. */
    public static final String NAME = "authorized_study_counts";

    /** The tool title. */
    public static final String TITLE = "Participants per study (authorized)";

    /** The tool description. */
    public static final String DESCRIPTION =
        """
            Count the participants in each study, as the signed-in user, through PIC-SURE's authorized channel. Every number is exact and \
            filtered by the user's own consents and study access, never an obfuscated open-access count, and the two must never be compared \
            or combined. It takes no arguments and needs the user's token. Use it for a question such as "how many participants are in each \
            study", before searching the dictionary: studies are not variables. basis says how the studies were found. study_consents means \
            the open channel listed study consent concepts and each study is one of them. dictionary_sample means it does not, so each study is a \
            dataset counted by the most-populated of a few demographic variables from it: that equals the dataset's size when one of them covers \
            everyone, and is otherwise a lower bound. Read the note, and report matchesTotal when it is there. A study with noData has no participants \
            the user can see.""";

    /** The concept path every study consent concept sits under. */
    static final String CONSENTS_ROOT = "\\_studies_consents\\";

    /** The dictionary facet category that names a concept's dataset. */
    static final String DATASET_CATEGORY = "dataset_id";

    /** The terms that find the concepts most participants have a value for, searched in each dataset. */
    static final List<String> CANDIDATE_TERMS = List.of("subject id", "sex", "gender", "age");

    /** How many candidate concepts are kept per dataset. */
    static final int CANDIDATES_PER_DATASET = 25;

    /** How long a dataset's candidates are trusted before the dictionary is asked again. */
    static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(StudyCountsTool.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final AuthorizedQueryClient authorized;
    private final OpenQueryClient open;
    private final DictionaryClient dictionary;
    private final Clock clock;

    private final Map<String, List<String>> candidates = new HashMap<>();
    private Instant cacheStarted;

    /**
     * Creates the tool.
     *
     * @param authorized the authorized query client
     * @param open the open query client, used only to see whether the data has study consents
     * @param dictionary the dictionary client
     */
    @Autowired
    public StudyCountsTool(AuthorizedQueryClient authorized, OpenQueryClient open, DictionaryClient dictionary) {
        this(authorized, open, dictionary, Clock.systemUTC());
    }

    StudyCountsTool(AuthorizedQueryClient authorized, OpenQueryClient open, DictionaryClient dictionary, Clock clock) {
        this.authorized = authorized;
        this.open = open;
        this.dictionary = dictionary;
        this.clock = clock;
    }

    /**
     * Counts the participants in each study.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param arguments the raw arguments of the {@code tools/call} request, which this tool ignores
     * @return the per-study counts
     * @throws ToolFailure with a model-facing message for a caller with no token, a failed query or dictionary call, or a response the tool
     *         cannot read
     */
    @AuditEvent(type = "QUERY", action = "query.sync")
    public StudyCountsResult handle(McpTransportContext context, Map<String, Object> arguments) {
        CallerHeaders caller = CallerHeaders.from(context);
        AuthorizedQueryCalls.requireToken(caller);
        Integer total = totalParticipants(caller);
        List<String> consents = consentPaths(caller);
        if (!consents.isEmpty()) {
            List<StudyCountsResult.Study> studies = new ArrayList<>();
            Map<String, AuthorizedCrossCountResult.Cell> cells = crossCount(consents, caller);
            for (String path : consents) {
                studies.add(study(path, cells.get(path)));
            }
            return new StudyCountsResult(StudyCountsResult.STUDY_CONSENTS, studies, total, null, null);
        }
        List<String> datasets = datasets(caller);
        if (datasets.isEmpty()) {
            throw new ToolFailure("The dictionary lists no datasets, so there is no per-study breakdown to give.");
        }
        Map<String, List<String>> byDataset = candidates(datasets, caller);
        Map<String, AuthorizedCrossCountResult.Cell> cells =
            crossCount(byDataset.values().stream().flatMap(List::stream).distinct().toList(), caller);
        List<StudyCountsResult.Study> studies = new ArrayList<>();
        int sum = 0;
        for (String dataset : datasets) {
            StudyCountsResult.Study study = study(dataset, largest(byDataset.getOrDefault(dataset, List.of()), cells));
            sum += study.count() == null ? 0 : study.count();
            studies.add(study);
        }
        Boolean matches = total == null ? null : sum == total;
        return new StudyCountsResult(StudyCountsResult.DICTIONARY_SAMPLE, studies, total, matches, dictionaryNote(matches));
    }

    private static String dictionaryNote(Boolean matches) {
        String basis = "This data has no study consent concepts, so each study is a dataset, counted as the participants with a value for "
            + "the most-populated of a few demographic variables from it. That is the dataset's size when one of them covers everyone, and "
            + "otherwise a lower bound.";
        if (Boolean.TRUE.equals(matches)) {
            return basis + " The counts add up to the total, so they are consistent with each participant being in one dataset.";
        }
        return basis
            + " Do not add the counts and call the sum the total: the datasets may overlap or a variable may miss some participants.";
    }

    private static StudyCountsResult.Study study(String name, AuthorizedCrossCountResult.Cell cell) {
        if (cell == null || cell.count() == null) {
            return new StudyCountsResult.Study(name, null, cell == null || Boolean.TRUE.equals(cell.noData()) ? true : null);
        }
        return new StudyCountsResult.Study(name, cell.count(), cell.count() == 0 ? true : null);
    }

    /** @return the study consent concept paths the open channel keys its CROSS_COUNT by, or none when the data has no study consents */
    private List<String> consentPaths(CallerHeaders caller) {
        Query query = new QueryInput(null, null, null).toQuery(ResultType.CROSS_COUNT);
        String body;
        try {
            body = QueryCalls.run(OpenQueryClient.QUERY_SYNC_PATH, () -> open.querySync(query, caller));
        } catch (ToolFailure e) {
            // Without an answer there is no evidence of consents, so the dictionary route runs; the failure is logged by QueryCalls.
            log.warn("Could not check for study consents; counting by dataset instead");
            return List.of();
        }
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode json = readObject(body);
        if (json == null) {
            return List.of();
        }
        // The body's own keys, not CrossCountResult's cells, which are capped at MAX_CELLS and would drop studies.
        List<String> paths = new ArrayList<>();
        json.fieldNames().forEachRemaining(path -> {
            if (path.startsWith(CONSENTS_ROOT) && !CONSENTS_ROOT.equals(path)) {
                paths.add(path);
            }
        });
        return paths;
    }

    private List<String> datasets(CallerHeaders caller) {
        List<FacetCategory> categories = DictionaryCalls.run(DictionaryClient.FACETS_PATH, () -> dictionary.listFacets("", caller));
        return categories.stream().filter(category -> DATASET_CATEGORY.equals(category.name()))
            .flatMap(
                category -> category.facets() == null ? java.util.stream.Stream.<FacetCategory.Facet>empty() : category.facets().stream()
            ).map(FacetCategory.Facet::name).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The candidate concept paths for each dataset, asking the dictionary for any dataset not remembered yet. The dictionary is open, so
     * nothing here depends on the caller.
     *
     * @return dataset name to candidate paths; a dataset with no concept has no entry
     */
    private synchronized Map<String, List<String>> candidates(List<String> datasets, CallerHeaders caller) {
        Instant now = clock.instant();
        if (cacheStarted == null || Duration.between(cacheStarted, now).compareTo(CACHE_TTL) >= 0) {
            candidates.clear();
            cacheStarted = now;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String dataset : datasets) {
            if (!candidates.containsKey(dataset)) {
                List<String> found = candidatesFor(dataset, caller);
                if (!found.isEmpty()) {
                    candidates.put(dataset, found);
                }
            }
            if (candidates.containsKey(dataset)) {
                result.put(dataset, candidates.get(dataset));
            }
        }
        return result;
    }

    /** The demographic-term hits first, and the dictionary's own first page for a dataset those terms find nothing in. */
    private List<String> candidatesFor(String dataset, CallerHeaders caller) {
        List<FacetFilter> facet = List.of(new FacetFilter(DATASET_CATEGORY, dataset));
        List<String> found = paths(
            DictionaryCalls.run(
                DictionaryClient.CONCEPTS_PATH,
                () -> dictionary.searchConceptTerms(CANDIDATE_TERMS, facet, 0, CANDIDATES_PER_DATASET, caller)
            )
        );
        if (!found.isEmpty()) {
            return found;
        }
        return paths(
            DictionaryCalls
                .run(DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts("", facet, 0, CANDIDATES_PER_DATASET, caller))
        );
    }

    private static List<String> paths(DictionaryPage page) {
        return page.content() == null ? List.of()
            : page.content().stream().map(DictionaryConcept::conceptPath).filter(Objects::nonNull).distinct().limit(CANDIDATES_PER_DATASET)
                .toList();
    }

    /** @return the candidate's cell with the largest exact count, or null when none of them has a count */
    private static AuthorizedCrossCountResult.Cell largest(List<String> paths, Map<String, AuthorizedCrossCountResult.Cell> cells) {
        AuthorizedCrossCountResult.Cell best = null;
        for (String path : paths) {
            AuthorizedCrossCountResult.Cell cell = cells.get(path);
            if (cell != null && cell.count() != null && (best == null || cell.count() > best.count())) {
                best = cell;
            }
        }
        return best;
    }

    /** Counts the paths in batches of the CROSS_COUNT select limit, as the caller. */
    private Map<String, AuthorizedCrossCountResult.Cell> crossCount(List<String> select, CallerHeaders caller) {
        Map<String, AuthorizedCrossCountResult.Cell> cells = new HashMap<>();
        for (int from = 0; from < select.size(); from += CrossCountResult.MAX_CELLS) {
            List<String> batch = select.subList(from, Math.min(from + CrossCountResult.MAX_CELLS, select.size()));
            Query query = new QueryInput(batch, null, null).toQuery(ResultType.CROSS_COUNT);
            JsonNode json = readObject(AuthorizedQueryCalls.run(authorized, query, caller));
            if (json == null) {
                throw new ToolFailure("The query service returned a result the tool could not read.");
            }
            for (AuthorizedCrossCountResult.Cell cell : AuthorizedCrossCountResult.from("CROSS_COUNT", json, batch).cells()) {
                // Only what was asked for: a path the caller did not select is not part of this count.
                if (batch.contains(cell.conceptPath())) {
                    cells.put(cell.conceptPath(), cell);
                }
            }
        }
        return cells;
    }

    private Integer totalParticipants(CallerHeaders caller) {
        Query query = new QueryInput(null, null, null).toQuery(ResultType.COUNT);
        CountResult parsed = CountResult.parse(AuthorizedQueryCalls.run(authorized, query, caller));
        return parsed.isExact() ? parsed.count() : null;
    }

    /** The argument object of {@code authorized_study_counts}, which takes none. */
    public record Input() {
    }

    private static JsonNode readObject(String body) {
        try {
            JsonNode json = MAPPER.readTree(body);
            return json != null && json.isObject() ? json : null;
        } catch (JsonProcessingException e) {
            log.warn("A query response was not JSON");
            return null;
        }
    }
}
