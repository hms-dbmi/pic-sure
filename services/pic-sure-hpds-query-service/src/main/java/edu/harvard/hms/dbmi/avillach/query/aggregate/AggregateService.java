package edu.harvard.hms.dbmi.avillach.query.aggregate;

import edu.harvard.dbmi.avillach.domain.BinRange;
import edu.harvard.dbmi.avillach.domain.ContinuousBinningRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

import edu.harvard.dbmi.avillach.domain.ContinuousBinningResponse;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.query.QueryService;

/**
 * Orchestrates querySync obfuscation, {@code CROSS_COUNT} query scoping through {@link #openCrossCount}, and chart suppression. This
 * DB-free module stores no {@code Query} rows; async open submissions ({@link #query}) delegate persistence and HPDS dispatch to
 * {@link QueryService}, which persists through operations-service. Audit logging is handled by the gateway; the controllers'
 * {@code @AuditEvent} labels are declarative.
 *
 * <p>The inbound query is a typed v3 {@link Query} and is never mutated. Each downstream request is a new value: the query itself, the
 * cross count derived from it by {@link #openCrossCount}, the study-consents search term, and the continuous counts sent for binning, each
 * wrapped in a {@link GeneralQueryRequest} envelope.
 *
 * <p><b>PRIVACY-CRITICAL:</b> {@link #ALLOWED_RESULT_TYPES} is the complete allow-list; a type not on it is rejected with a 400 rather than
 * silently forwarded. A COUNT runs as the study-consents CROSS_COUNT and returns its obfuscated total. The per-type dispatch in
 * {@link #getExpectedResponse} determines which types are obfuscated (COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT)
 * versus a raw pass-through (INFO_COLUMN_LISTING, AGGREGATE_VCF_EXCERPT, and VCF_EXCERPT). OBSERVATION_CROSS_COUNT and
 * VARIANT_COUNT_FOR_QUERY are not served: both return exact counts for the query's cohort. Any divergence here is a privacy regression.
 */
@Service
public class AggregateService {

    /**
     * Result types accepted by {@code querySync}. A body naming a result type that {@link ResultType} does not define fails to bind and
     * answers 400 before reaching this service.
     */
    private static final Set<ResultType> ALLOWED_RESULT_TYPES = Set.of(
        ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.INFO_COLUMN_LISTING, ResultType.CATEGORICAL_CROSS_COUNT,
        ResultType.CONTINUOUS_CROSS_COUNT, ResultType.AGGREGATE_VCF_EXCERPT, ResultType.VCF_EXCERPT
    );

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AggregateBackendClient backend;
    private final ObfuscationService obfuscation;
    private final AggregateProperties props;
    private final QueryService queryService;
    private final ConceptMetadataLookup conceptMetadata;
    private final StudyConsentPathProvider studyConsentPaths;

    public AggregateService(
        AggregateBackendClient backend, ObfuscationService obfuscation, AggregateProperties props, QueryService queryService,
        ConceptMetadataLookup conceptMetadata, StudyConsentPathProvider studyConsentPaths
    ) {
        this.backend = backend;
        this.obfuscation = obfuscation;
        this.props = props;
        this.queryService = queryService;
        this.conceptMetadata = conceptMetadata;
        this.studyConsentPaths = studyConsentPaths;
    }

    /**
     * Validates an async open-channel submission. A {@code CROSS_COUNT} query is replaced by {@link #openCrossCount} before persistence and
     * dispatch, which injects the full study-consents allow-list under {@code select}. Other result types are dispatched as sent; the
     * allow-list applies only to {@code querySync}.
     *
     * <p>Persistence and HPDS dispatch are delegated to {@link QueryService}, so the replaced query is the one stored. Later status,
     * result, signed-url, and metadata calls through {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryController} therefore
     * operate on the consent-scoped query.
     *
     * @param query the query the caller submitted, or null when the body carried none
     * @return the status HPDS answered with, carrying the PIC-SURE result id
     * @throws PicsureException 400 when the query or its {@code expectedResultType} is missing
     */
    public QueryStatus query(Query query) {
        ResultType expectedResultType = requireExpectedResultType(query);
        Query dispatched = expectedResultType == ResultType.CROSS_COUNT ? openCrossCount(query) : query;
        return queryService.query(HpdsBackendSelector.OPEN, envelope(dispatched));
    }

    /**
     * Runs an open query inline and obfuscates its result.
     *
     * @param query the query the caller sent, or null when the body carried none
     * @return the obfuscated body as {@code application/json}, with HPDS's {@code queryMetadata} header when it sent one
     * @throws PicsureException 400 when the query or its {@code expectedResultType} is missing, or the type is not on the allow-list
     */
    public ResponseEntity<String> querySync(Query query) {
        ResultType expectedResultType = requireExpectedResultType(query);
        if (!ALLOWED_RESULT_TYPES.contains(expectedResultType)) {
            logger.warn("Incorrect Result Type: {}", expectedResultType);
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrect result type: " + expectedResultType);
        }

        boolean crossCountShaped = expectedResultType == ResultType.CROSS_COUNT || expectedResultType == ResultType.COUNT;
        Query dispatched = crossCountShaped ? openCrossCount(query) : query;
        ResponseEntity<String> backendResp = backend.querySync(envelope(dispatched));
        String responseString = getExpectedResponse(expectedResultType, backendResp.getBody(), query);

        ResponseEntity.BodyBuilder out = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        String metadata = backendResp.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD);
        if (metadata != null) {
            out.header(AggregateBackendClient.QUERY_METADATA_FIELD, metadata);
        }
        return out.body(responseString);
    }

    /**
     * Dispatches on {@code expectedResultType} to the matching obfuscation path. Types that are allowed but not obfuscated
     * (INFO_COLUMN_LISTING, AGGREGATE_VCF_EXCERPT, VCF_EXCERPT) fall through unmodified.
     */
    private String getExpectedResponse(ResultType expectedResultType, String entityString, Query query) {
        try {
            switch (expectedResultType) {
                case COUNT:
                    return obfuscation.processCount(entityString);
                case CROSS_COUNT:
                    return objectMapper.writeValueAsString(obfuscation.processCrossCounts(entityString));
                case CATEGORICAL_CROSS_COUNT:
                    return obfuscation
                        .processCategoricalCrossCounts(entityString, getCrossCountForQuery(query), categoryValuesFor(entityString));
                case CONTINUOUS_CROSS_COUNT:
                    return processContinuousCrossCounts(entityString, getCrossCountForQuery(query));
                default:
                    return entityString;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error processing aggregate response", e);
        }
    }

    /** Dataset-wide values of each concept in a categorical result, so a chart naming all of them can omit the empty grouped bucket. */
    private Map<String, Set<String>> categoryValuesFor(String categoricalJson) throws IOException {
        if (categoricalJson == null) {
            return Map.of();
        }
        Set<String> concepts = new LinkedHashSet<>();
        objectMapper.readTree(categoricalJson).fieldNames().forEachRemaining(concepts::add);
        return conceptMetadata.categoryValues(concepts);
    }

    /** No matter the type, fetch the CROSS_COUNT incl. ALL study consents (used for chart suppression). */
    private String getCrossCountForQuery(Query query) {
        return backend.querySync(envelope(openCrossCount(query))).getBody();
    }

    private String processContinuousCrossCounts(String continuousJson, String crossCountJson) throws IOException {
        if (continuousJson == null || crossCountJson == null) {
            return null;
        }
        Map<String, String> crossCounts = objectMapper.readValue(crossCountJson, new TypeReference<>() {});
        if (obfuscation.shouldSuppressChart(crossCounts)) {
            return null;
        }

        if (props.hasVisualization()) {
            Map<String, Map<String, Integer>> continuous = objectMapper.readValue(continuousJson, new TypeReference<>() {});
            Map<String, Map<String, Object>> binned = getBinnedContinuousCrossCount(continuous, obfuscation.maxChartBins(crossCounts));
            return objectMapper.writeValueAsString(obfuscation.obfuscateChartCounts(binned));
        } else {
            Map<String, Map<String, Object>> continuous = objectMapper.readValue(continuousJson, new TypeReference<>() {});
            return objectMapper.writeValueAsString(obfuscation.obfuscateChartCounts(continuous));
        }
    }

    /**
     * Sends continuous results to the configured visualization URL for binning and reads the bins out of the
     * {@link ContinuousBinningResponse} it answers with. Each chart spans its concept's overall range, a concept with no known range is
     * left out, and charts are capped at {@code maxBins}. Concept order and bin order are kept as the visualization service sent them. A
     * body that carries no {@code bins}, which is what a visualization service older than the record answers with, is treated as a failed
     * upstream call and never as an empty result.
     */
    private Map<String, Map<String, Object>> getBinnedContinuousCrossCount(Map<String, Map<String, Integer>> continuous, int maxBins)
        throws IOException {
        Map<String, BinRange> ranges = conceptMetadata.overallRanges(continuous.keySet());
        continuous.keySet().retainAll(ranges.keySet());
        String binResponse = backend.binContinuous(new ContinuousBinningRequest(continuous, ranges, maxBins));
        ContinuousBinningResponse binned = objectMapper.readValue(binResponse, ContinuousBinningResponse.class);
        if (binned == null || binned.bins() == null) {
            throw new HpdsCommunicationException("Visualization bin/continuous response carried no bins");
        }
        Map<String, Map<String, Object>> bins = new LinkedHashMap<>();
        binned.bins().forEach((conceptPath, counts) -> bins.put(conceptPath, new LinkedHashMap<>(counts)));
        return bins;
    }

    /**
     * Derives the open cross count of a query: the same filters, {@code expectedResultType} forced to {@code CROSS_COUNT}, and
     * {@code select} replaced by every study-consents concept path the open backend lists. The query passed in is not changed.
     *
     * @param query the query to derive from
     * @return a new query selecting the full study-consents allow-list
     */
    private Query openCrossCount(Query query) {
        return new Query(
            studyConsentPaths.studyConsentPaths(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(),
            ResultType.CROSS_COUNT, query.picsureId(), query.id()
        );
    }

    /** Wraps a typed payload in the envelope every downstream endpoint reads its {@code query} member from. */
    private static QueryRequest envelope(Object payload) {
        return new GeneralQueryRequest().setQuery(payload);
    }

    private static ResultType requireExpectedResultType(Query query) {
        if (query == null || query.expectedResultType() == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        return query.expectedResultType();
    }
}
