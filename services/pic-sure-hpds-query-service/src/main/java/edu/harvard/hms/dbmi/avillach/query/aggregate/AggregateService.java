package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.hms.dbmi.avillach.query.search.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.query.QueryService;

/**
 * Direct port of {@code AggregateDataSharingResourceRSV3}'s orchestration (the v1 sibling's ingress is gone) -- the querySync obfuscation
 * dispatch, the {@code CROSS_COUNT} query alteration ({@code changeQueryToOpenCrossCount}), and the continuous-suppression rule. Stores no
 * {@code Query} rows itself (this module is DB-free); the async open submit ({@link #query}) delegates persistence and HPDS dispatch to
 * {@link QueryService} (which persists over HTTP via operations-service), and does not implement {@code RequestScopedHeader} (dead code in
 * the WAR -- dropped) or perform inline audit logging (handled in the gateway).
 *
 * <p><b>Typed throughout.</b> The query is the v3 {@link Query} record from ingress to HPDS hop; the WAR's {@code JsonNode} surgery
 * ({@code valueToTree}, {@code findParents("expectedResultType")}, the {@code LinkedHashMap} consent injection) is gone. The consent
 * scoping is now a record rebuild -- {@link #toOpenCrossCount} -- which cannot silently miss a field name or leave the result type
 * unchanged the way a field-name-driven mutation could.
 *
 * <p><b>PRIVACY-CRITICAL:</b> {@link #ALLOWED_RESULT_TYPES} is the WAR's {@code querySync} allow-list; a type not on it is rejected with a
 * 400 rather than silently forwarded. (The WAR listed one further name, {@code OBSERVATION_COUNT}, which no longer exists in the v3
 * {@link ResultType} enum -- such a submission cannot bind at the ingress at all, so it is a 400 there instead of here.) The per-type
 * dispatch in {@link #getExpectedResponse} determines which types get threshold/variance obfuscation (COUNT, CROSS_COUNT,
 * CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT) versus a raw pass-through (INFO_COLUMN_LISTING, OBSERVATION_CROSS_COUNT,
 * VARIANT_COUNT_FOR_QUERY, AGGREGATE_VCF_EXCERPT, VCF_EXCERPT -- none of these were obfuscated in the WAR either). Any divergence here is a
 * privacy regression.
 */
@Service
public class AggregateService {

    private static final String STUDIES_CONSENTS_PATH = "\\_studies_consents\\";

    /**
     * Port of the WAR's {@code querySync} allow-list (identical in v1 and v3), typed as the enum. The WAR's tenth entry,
     * {@code OBSERVATION_COUNT}, has no v3 {@link ResultType} constant and so cannot reach this check.
     *
     * <p>Package-private so {@code AggregateServiceTest} can drive EVERY allow-listed type through {@link #getExpectedResponse}: adding a
     * type here without deciding its obfuscation treatment there must be a test failure, not a silent pass-through.
     */
    static final Set<ResultType> ALLOWED_RESULT_TYPES = Set.of(
        ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.INFO_COLUMN_LISTING, ResultType.OBSERVATION_CROSS_COUNT,
        ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT, ResultType.VARIANT_COUNT_FOR_QUERY,
        ResultType.AGGREGATE_VCF_EXCERPT, ResultType.VCF_EXCERPT
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
     * Async open-channel submit. Direct port of the WAR aggregate resources' {@code query()} entry point
     * ({@code AggregateDataSharingResourceRSV3.query}): validate the request and -- only for a {@code CROSS_COUNT} submission -- rebuild it
     * via {@link #toOpenCrossCount} (force {@code CROSS_COUNT} + put the full study-consents allow-list in {@code select}) BEFORE it is
     * persisted and dispatched. Other result types are forwarded unchanged, exactly as the WAR's async {@code query()} did (it had no
     * allow-list; that guard existed only on {@code querySync}).
     *
     * <p><b>Consent-scoping fix (I6):</b> persistence + HPDS dispatch are delegated to {@link QueryService} (the module's DB-free create
     * flow), so the STORED query is the REWRITTEN one. Every later status/result/signed-url/metadata call -- served by the v3 read ingress
     * ({@code /hpds/{backend}/v3/...}, {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller}) off the stored query --
     * therefore operates on the safe, consent-scoped query rather than the raw submission. This closes the gap where an unwired open async
     * path dispatched the raw query with no consent-scoping.
     */
    public QueryStatusResponse query(Query query) {
        ResultType resultType = requireExpectedResultType(query);
        Query dispatched = resultType == ResultType.CROSS_COUNT ? toOpenCrossCount(query) : query;
        return queryService.queryV3(HpdsBackendSelector.OPEN, dispatched);
    }

    // ---- the obfuscation core ----

    public ResponseEntity<String> querySync(Query query) {
        ResultType resultType = requireExpectedResultType(query);
        if (!ALLOWED_RESULT_TYPES.contains(resultType)) {
            logger.warn("Incorrect Result Type: {}", resultType);
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrect result type: " + resultType);
        }

        Query dispatched = resultType == ResultType.CROSS_COUNT ? toOpenCrossCount(query) : query;
        ResponseEntity<String> backendResp = backend.querySync(dispatched);
        String responseString = getExpectedResponse(resultType, backendResp.getBody(), query);

        ResponseEntity.BodyBuilder out = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        String metadata = backendResp.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD);
        if (metadata != null) {
            out.header(AggregateBackendClient.QUERY_METADATA_FIELD, metadata);
        }
        return out.body(responseString);
    }

    /**
     * Dispatches on the result type to the matching obfuscation path. Every {@link #ALLOWED_RESULT_TYPES} member is named EXPLICITLY here,
     * including the five the WAR allow-listed but never obfuscated (INFO_COLUMN_LISTING, OBSERVATION_CROSS_COUNT, VARIANT_COUNT_FOR_QUERY,
     * AGGREGATE_VCF_EXCERPT, VCF_EXCERPT), which pass through unmodified.
     *
     * <p><b>No silent pass-through default.</b> A raw-return {@code default} would mean that widening the allow-list without deciding the
     * new type's obfuscation treatment leaks its unobfuscated payload -- a privacy regression that compiles and passes. The default throws
     * instead, so the omission is loud. It is unreachable for the current allow-list ({@link #querySync} rejects everything else with a 400
     * first), which is exactly what {@code AggregateServiceTest} pins by driving all nine types through here.
     */
    private String getExpectedResponse(ResultType resultType, String entityString, Query query) {
        try {
            return switch (resultType) {
                case COUNT -> obfuscation.obfuscateCount(entityString);
                case CROSS_COUNT -> objectMapper.writeValueAsString(obfuscation.processCrossCounts(entityString));
                case CATEGORICAL_CROSS_COUNT -> obfuscation.processCategoricalCrossCounts(entityString, getCrossCountForQuery(query));
                case CONTINUOUS_CROSS_COUNT -> processContinuousCrossCounts(entityString, getCrossCountForQuery(query));
                case INFO_COLUMN_LISTING, OBSERVATION_CROSS_COUNT, VARIANT_COUNT_FOR_QUERY, AGGREGATE_VCF_EXCERPT, VCF_EXCERPT -> entityString;
                default -> throw new IllegalStateException(
                    "Result type " + resultType + " is allow-listed but has no obfuscation treatment; refusing to return its raw payload"
                );
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Error processing aggregate response", e);
        }
    }

    /** No matter the type, fetch the CROSS_COUNT incl. ALL study consents (used for variance + suppression). */
    private String getCrossCountForQuery(Query query) {
        return backend.querySync(toOpenCrossCount(query)).getBody();
    }

    private String processContinuousCrossCounts(String continuousJson, String crossCountJson) throws IOException {
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
            Map<String, Map<String, Object>> binned = getBinnedContinuousCrossCount(continuous);
            return objectMapper.writeValueAsString(obfuscation.obfuscateCrossCount(generatedVariance, binned));
        } else {
            Map<String, Map<String, Object>> continuous = objectMapper.readValue(continuousJson, new TypeReference<>() {});
            return objectMapper.writeValueAsString(obfuscation.obfuscateCrossCount(generatedVariance, continuous));
        }
    }

    /**
     * Replaces the WAR's {@code ResourceRepository.getById(visualizationResourceId)} with the configured viz URL (DB-free). The binning hop
     * carries only the continuous counts: the WAR's {@code resourceUUID}/{@code resourceCredentials} echo went with the envelope.
     *
     * <p>Viz answers with its named {@code BinnedDistribution} wrapper, so the bins are unwrapped from {@code bins} rather than read off
     * the response root.
     */
    private Map<String, Map<String, Object>> getBinnedContinuousCrossCount(Map<String, Map<String, Integer>> continuous)
        throws IOException {
        return objectMapper.readValue(backend.binContinuous(continuous), BinnedDistributionResponse.class).bins();
    }

    /**
     * The visualization service's {@code POST /v3/bin/continuous} response ({@code BinnedDistribution}). Modelled here rather than imported
     * because that record is viz-local; unknown properties are NOT tolerated, so viz changing this shape fails loudly here instead of
     * quietly binning nothing. Values stay {@code Object} because {@link ObfuscationService#obfuscateCrossCount} takes the widened map.
     */
    private record BinnedDistributionResponse(Map<String, Map<String, Object>> bins) {

        private BinnedDistributionResponse {
            bins = bins == null ? Map.of() : bins;
        }
    }

    // ---- query rebuild (typed) ----

    /**
     * The consent-scoping rebuild that replaces the WAR's {@code changeQueryToOpenCrossCount} JSON surgery: a NEW {@link Query} forced to
     * {@link ResultType#CROSS_COUNT} whose {@code select} is the full study-consents allow-list, carrying the caller's filters and ids
     * through unchanged. Same semantics as before -- {@code select} is REPLACED, not merged -- but expressed as a record copy, so a renamed
     * component is a compile error rather than a silently skipped mutation.
     */
    private Query toOpenCrossCount(Query query) {
        return new Query(
            studyConsentAllowList(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), ResultType.CROSS_COUNT,
            query.picsureId(), query.id()
        );
    }

    /** The consent allow-list: every phenotype concept path HPDS reports under the studies-consents search. */
    private List<String> studyConsentAllowList() {
        SearchResults consentResults = backend.search(new SearchRequest(STUDIES_CONSENTS_PATH));
        LinkedHashMap<String, Object> resultsMap = objectMapper.convertValue(consentResults.results(), new TypeReference<>() {});
        LinkedHashMap<String, Object> phenotypes = objectMapper.convertValue(resultsMap.get("phenotypes"), new TypeReference<>() {});
        return List.copyOf(phenotypes.keySet());
    }

    // ---- guards ----

    /** A missing query or a query with no result type is the caller's error (the WAR's MISSING_DATA), never a forwarded request. */
    private static ResultType requireExpectedResultType(Query query) {
        if (query == null || query.expectedResultType() == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        return query.expectedResultType();
    }
}
