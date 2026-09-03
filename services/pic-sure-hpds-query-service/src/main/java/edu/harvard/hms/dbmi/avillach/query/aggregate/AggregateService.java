package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.query.QueryService;

/**
 * Orchestrates querySync obfuscation, {@code CROSS_COUNT} query scoping through {@code changeQueryToOpenCrossCount}, and continuous-result
 * suppression. This DB-free module stores no {@code Query} rows; async open submissions ({@link #query}) delegate persistence and HPDS
 * dispatch to {@link QueryService}, which persists through operations-service. Audit logging is handled by the gateway.
 *
 * <p><b>PRIVACY-CRITICAL:</b> {@link #ALLOWED_RESULT_TYPES} is the complete 10-type allow-list; a type not on it is rejected with a 400
 * rather than silently forwarded. The per-type dispatch in {@link #getExpectedResponse} determines which types get threshold and variance
 * obfuscation (COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT) versus a raw pass-through (INFO_COLUMN_LISTING,
 * OBSERVATION_COUNT, OBSERVATION_CROSS_COUNT, VARIANT_COUNT_FOR_QUERY, AGGREGATE_VCF_EXCERPT, and VCF_EXCERPT). Any divergence here is a
 * privacy regression.
 */
@Service
public class AggregateService {

    private static final String STUDIES_CONSENTS_PATH = "\\_studies_consents\\";

    /** Result types accepted by {@code querySync} for both v1 and v3. */
    private static final Set<String> ALLOWED_RESULT_TYPES = Set.of(
        "COUNT", "CROSS_COUNT", "INFO_COLUMN_LISTING", "OBSERVATION_COUNT", "OBSERVATION_CROSS_COUNT", "CATEGORICAL_CROSS_COUNT",
        "CONTINUOUS_CROSS_COUNT", "VARIANT_COUNT_FOR_QUERY", "AGGREGATE_VCF_EXCERPT", "VCF_EXCERPT"
    );

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AggregateBackendClient backend;
    private final ObfuscationService obfuscation;
    private final AggregateProperties props;
    private final QueryService queryService;

    public AggregateService(
        AggregateBackendClient backend, ObfuscationService obfuscation, AggregateProperties props, QueryService queryService
    ) {
        this.backend = backend;
        this.obfuscation = obfuscation;
        this.props = props;
        this.queryService = queryService;
    }

    // ---- async open submit ----

    /**
     * Validates an async open-channel submission. A {@code CROSS_COUNT} request is rewritten through {@link #changeQueryToOpenCrossCount}
     * before persistence and dispatch, forcing {@code CROSS_COUNT} and injecting the full study-consents allow-list under the variant's
     * consent field. Other result types pass through unchanged; the allow-list applies only to {@code querySync}.
     *
     * <p>Persistence and HPDS dispatch are delegated to {@link QueryService}, so the rewritten query is the one stored. Later status,
     * result, signed-url, and metadata calls through {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller} therefore
     * operate on the consent-scoped query.
     */
    public QueryStatus query(QueryRequest req, AggregateVariant variant) {
        checkQuery(req);
        JsonNode node = objectMapper.valueToTree(req.getQuery());
        req.setQuery(node);
        requireExpectedResultType(node);
        if ("CROSS_COUNT".equalsIgnoreCase(node.get("expectedResultType").asText())) {
            changeQueryToOpenCrossCount(req, variant);
        }
        return variant == AggregateVariant.V3 ? queryService.queryV3(HpdsBackendSelector.OPEN, req)
            : queryService.query(HpdsBackendSelector.OPEN, req);
    }

    // ---- the obfuscation core ----

    public ResponseEntity<String> querySync(QueryRequest req, AggregateVariant variant) {
        checkQuery(req);
        JsonNode node = objectMapper.valueToTree(req.getQuery());
        req.setQuery(node);
        requireExpectedResultType(node);

        String expectedResultType = node.get("expectedResultType").asText();
        if (!ALLOWED_RESULT_TYPES.contains(expectedResultType)) {
            logger.warn("Incorrect Result Type: {}", expectedResultType);
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrect result type: " + expectedResultType);
        }

        if ("CROSS_COUNT".equalsIgnoreCase(expectedResultType)) {
            changeQueryToOpenCrossCount(req, variant);
        }

        ResponseEntity<String> backendResp = backend.querySync(req, variant);
        String entityString = backendResp.getBody();
        String responseString = getExpectedResponse(expectedResultType, entityString, req, variant);

        ResponseEntity.BodyBuilder out = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        String metadata = backendResp.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD);
        if (metadata != null) {
            out.header(AggregateBackendClient.QUERY_METADATA_FIELD, metadata);
        }
        return out.body(responseString);
    }

    /**
     * Dispatches on {@code expectedResultType} to the matching obfuscation path. Types that are allowed but not obfuscated
     * (INFO_COLUMN_LISTING, OBSERVATION_COUNT, OBSERVATION_CROSS_COUNT, VARIANT_COUNT_FOR_QUERY, AGGREGATE_VCF_EXCERPT, VCF_EXCERPT) fall
     * through unmodified.
     */
    private String getExpectedResponse(String expectedResultType, String entityString, QueryRequest req, AggregateVariant variant) {
        try {
            switch (expectedResultType) {
                case "COUNT":
                    return obfuscation.obfuscateCount(entityString);
                case "CROSS_COUNT":
                    return objectMapper.writeValueAsString(obfuscation.processCrossCounts(entityString));
                case "CATEGORICAL_CROSS_COUNT":
                    return obfuscation.processCategoricalCrossCounts(entityString, getCrossCountForQuery(req, variant));
                case "CONTINUOUS_CROSS_COUNT":
                    return processContinuousCrossCounts(entityString, getCrossCountForQuery(req, variant), req, variant);
                default:
                    return entityString;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error processing aggregate response", e);
        }
    }

    /** No matter the type, fetch the CROSS_COUNT incl. ALL study consents (used for variance + suppression). */
    private String getCrossCountForQuery(QueryRequest req, AggregateVariant variant) {
        changeQueryToOpenCrossCount(req, variant);
        return backend.querySync(req, variant).getBody();
    }

    private String processContinuousCrossCounts(String continuousJson, String crossCountJson, QueryRequest req, AggregateVariant variant)
        throws IOException {
        if (continuousJson == null || crossCountJson == null) {
            return null;
        }
        Map<String, String> crossCounts = objectMapper.readValue(crossCountJson, new TypeReference<>() {});
        int generatedVariance = obfuscation.generateVarianceWithCrossCounts(crossCounts);

        if (obfuscation.shouldSuppressContinuousCrossCounts(crossCounts)) {
            return null;
        }

        if (props.hasVisualization()) {
            Map<String, Map<String, Integer>> continuous = objectMapper.readValue(continuousJson, new TypeReference<>() {});
            Map<String, Map<String, Object>> binned = getBinnedContinuousCrossCount(req, continuous, variant);
            return objectMapper.writeValueAsString(obfuscation.obfuscateCrossCount(generatedVariance, binned));
        } else {
            Map<String, Map<String, Object>> continuous = objectMapper.readValue(continuousJson, new TypeReference<>() {});
            return objectMapper.writeValueAsString(obfuscation.obfuscateCrossCount(generatedVariance, continuous));
        }
    }

    /** Sends continuous results to the configured visualization URL for binning. */
    private Map<String, Map<String, Object>> getBinnedContinuousCrossCount(
        QueryRequest req, Map<String, Map<String, Integer>> continuous, AggregateVariant variant
    ) throws IOException {
        QueryRequest vizRequest = new GeneralQueryRequest();
        vizRequest.setQuery(continuous);
        vizRequest.setResourceCredentials(req.getResourceCredentials());
        String vizId = props.getVisualizationResourceId();
        if (vizId != null && !vizId.isBlank()) {
            vizRequest.setResourceUUID(UUID.fromString(vizId));
        }
        String binResponse = backend.binContinuous(vizRequest, variant);
        return objectMapper.readValue(binResponse, new TypeReference<>() {});
    }

    // ---- query mutation (variant-aware) ----

    /** Sets expectedResultType to CROSS_COUNT and injects the full study-consents allow-list under the variant's consents field. */
    private QueryRequest changeQueryToOpenCrossCount(QueryRequest req, AggregateVariant variant) {
        JsonNode node = objectMapper.valueToTree(req.getQuery());
        JsonNode withCrossCount = setExpectedResultTypeToCrossCount(node);
        JsonNode withConsents = addStudyConsentsToQuery(withCrossCount, variant);
        req.setQuery(withConsents);
        return req;
    }

    private JsonNode setExpectedResultTypeToCrossCount(JsonNode jsonNode) {
        for (JsonNode parent : jsonNode.findParents("expectedResultType")) {
            ((ObjectNode) parent).put("expectedResultType", "CROSS_COUNT");
        }
        return jsonNode;
    }

    private JsonNode addStudyConsentsToQuery(JsonNode jsonNode, AggregateVariant variant) {
        SearchResults consentResults = getAllStudyConsents();
        LinkedHashMap<String, Object> resultsMap = objectMapper.convertValue(consentResults.getResults(), new TypeReference<>() {});
        LinkedHashMap<String, Object> phenotypes = objectMapper.convertValue(resultsMap.get("phenotypes"), new TypeReference<>() {});

        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (String key : phenotypes.keySet()) {
            arrayNode.add(key);
        }
        ((ObjectNode) jsonNode).set(variant.consentsField, arrayNode); // v1: crossCountFields, v3: select
        return jsonNode;
    }

    private SearchResults getAllStudyConsents() {
        QueryRequest studiesConsents = new GeneralQueryRequest();
        studiesConsents.setQuery(STUDIES_CONSENTS_PATH);
        return backend.search(studiesConsents); // /search -- NO /v3 prefix in either variant
    }

    // ---- guards ----

    private static void checkQuery(QueryRequest req) {
        if (req == null || req.getQuery() == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
    }

    private static void requireExpectedResultType(JsonNode node) {
        if (!node.has("expectedResultType")) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
    }
}
