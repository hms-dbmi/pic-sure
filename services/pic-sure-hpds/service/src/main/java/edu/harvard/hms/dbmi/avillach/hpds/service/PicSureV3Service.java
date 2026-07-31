package edu.harvard.hms.dbmi.avillach.hpds.service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * HPDS's v3 HTTP surface. Every hop here is typed against the shared {@code pic-sure-contracts} records, and the query itself crosses the
 * wire BARE: {@code /query} and {@code /query/sync} bind {@link Query} directly, with no {@code QueryRequest} envelope to unwrap and no
 * string-encoded query to re-parse. The reads carry no body at all -- HPDS already holds the query behind the {@code resourceQueryId}, so
 * {@code /query/{id}/status} is a GET and {@code /query/{id}/result} and {@code /query/{id}/signed-url} are bodyless POSTs (POST rather
 * than GET because they are audited data-access events, not cacheable reads).
 *
 * <p>The matching client half is {@code pic-sure-hpds-query-service}'s {@code ResourceWebClient}; the two are pinned against each other by
 * {@code PicSureV3ServiceWebTest} here and {@code ResourceWebClientTest} there.
 */
@RequestMapping(value = "PIC-SURE/v3", produces = "application/json")
@RestController
public class PicSureV3Service {

    @Autowired
    public PicSureV3Service(
        QueryV3Service queryService, CountV3Processor countProcessor, VariantListV3Processor variantListProcessor,
        QueryExecutor queryExecutor, Paginator paginator, SignUrlService signUrlService, FileSharingV3Service fileSharingService,
        TestDataService testDataService
    ) {
        this.queryService = queryService;
        this.countProcessor = countProcessor;
        this.variantListProcessor = variantListProcessor;
        this.queryExecutor = queryExecutor;
        this.paginator = paginator;
        this.fileSharingService = fileSharingService;
        this.signUrlService = signUrlService;
        this.testDataService = testDataService;
        Crypto.loadDefaultKey();
    }

    private final QueryV3Service queryService;

    private static final Logger log = LoggerFactory.getLogger(PicSureV3Service.class);

    private final CountV3Processor countProcessor;

    private final VariantListV3Processor variantListProcessor;

    private final QueryExecutor queryExecutor;

    private final Paginator paginator;

    private final SignUrlService signUrlService;

    private final FileSharingV3Service fileSharingService;

    private final TestDataService testDataService;

    @Autowired
    private HttpServletRequest httpRequest;

    private static final String QUERY_METADATA_FIELD = "queryMetadata";

    /**
     * Describes this resource and the query format it accepts. Takes no request body: the old {@code QueryRequest} argument was never read.
     */
    @AuditEvent(type = "OTHER", action = "info")
    @PostMapping("/info")
    public ResourceInfo info() {
        List<QueryFormat> queryFormats = List.of();

        try {
            queryFormats = ImmutableList.of(
                new QueryFormat(
                    "PhenoCube Query Format", "PhenoCube Query Format",
                    ImmutableMap.of(
                        "fields",
                        "A list of field names. Can be any key from the results map returned from the search endpoint of this resource. Unless filters are set, the included fields will be returned for all patients as a sparse matrix.",
                        "numericFilters",
                        "A map where each entry maps a field name to an object with min and/or max properties. Patients without a value between the min and max will not be included in the result set.",
                        "requiredFields",
                        "A list of field names for which a patient must have a value in order to be inclued in the result set.",
                        "categoryFilters", "A map where each entry maps a field name to a list of values to be included in the result set."
                    ),
                    ImmutableList.of(
                        ImmutableMap.of(
                            "Demographics and interesting variables for people with high blood pressure",
                            new ObjectMapper().readValue(
                                "{\"fields\":[\"\\\\demographics\\\\SEX\\\\\",\"\\\\demographics\\\\WTMEC2YR\\\\\",\"\\\\demographics\\\\WTMEC4YR\\\\\",\"\\\\demographics\\\\area\\\\\",\"\\\\demographics\\\\education\\\\\",\"\\\\examination\\\\blood pressure\\\\60 sec HR (30 sec HR * 2)\\\\\",\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\",\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\",\"\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\",\"\\\\examination\\\\body measures\\\\Head BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Head Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Pelvis BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Spine BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Maximal Calf Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Recumbent Length (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Standing Height (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Subscapular Skinfold (mm)\\\\\"],"
                                    + "\"numericFilters\":{\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\":{\"min\":120},\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\":{\"min\":80}}}",
                                Map.class
                            )
                        ),
                        ImmutableMap.of(
                            "Demographics and interesting variables for men with high blood pressure who live with a smoker and for whom we have BMI data",
                            ImmutableMap.of(
                                "fields",
                                ImmutableList.of(
                                    "\\demographics\\SEX\\", "\\demographics\\WTMEC2YR\\", "\\demographics\\WTMEC4YR\\",
                                    "\\demographics\\area\\", "\\demographics\\education\\",
                                    "\\examination\\blood pressure\\60 sec HR (30 sec HR * 2)\\",
                                    "\\examination\\blood pressure\\mean diastolic\\", "\\examination\\blood pressure\\mean systolic\\",
                                    "\\examination\\body measures\\Body Mass Index (kg per m**2)\\",
                                    "\\examination\\body measures\\Head BMD (g per cm^2)\\",
                                    "\\examination\\body measures\\Head Circumference (cm)\\",
                                    "\\examination\\body measures\\Lumber Pelvis BMD (g per cm^2)\\",
                                    "\\examination\\body measures\\Lumber Spine BMD (g per cm^2)\\",
                                    "\\examination\\body measures\\Maximal Calf Circumference (cm)\\",
                                    "\\examination\\body measures\\Recumbent Length (cm)\\",
                                    "\\examination\\body measures\\Standing Height (cm)\\",
                                    "\\examination\\body measures\\Subscapular Skinfold (mm)\\"
                                ), "requiredFields", ImmutableList.of("\\examination\\body measures\\Body Mass Index (kg per m**2)\\"),
                                "numericFilters",
                                ImmutableMap.of(
                                    "\\examination\\blood pressure\\mean systolic\\", ImmutableMap.of("min", 120),
                                    "\\examination\\blood pressure\\mean diastolic\\", ImmutableMap.of("min", 80)
                                ), "categoryFilters",
                                ImmutableMap.of(
                                    "\\demographics\\SEX\\", ImmutableList.of("male"),
                                    "\\questionnaire\\smoking family\\Does anyone smoke in home?\\", ImmutableList.of("Yes")
                                )
                            )
                        )
                    )
                )
            );
        } catch (JsonParseException e) {
            log.error("JsonParseException  caught: ", e);
        } catch (JsonMappingException e) {
            log.error("JsonMappingException  caught: ", e);
        } catch (IOException e) {
            log.error("IOException  caught: ", e);
        }
        // TODO examples, examples, examples, specification
        return new ResourceInfo(UUID.randomUUID(), "PhenoCube v1.0-SNAPSHOT", queryFormats);
    }

    @AuditEvent(type = "SEARCH", action = "search")
    @PostMapping("/search")
    public SearchResults search(@RequestBody SearchRequest searchJson) {
        String searchTerm = searchJson.query();
        if (searchTerm != null) {
            AuditAttributes.putMetadata(httpRequest, "search_term", searchTerm);
        }
        Set<Entry<String, ColumnMeta>> allColumns = queryExecutor.getDictionary().entrySet();

        // Phenotype Values
        Object phenotypeResults = searchTerm != null ? allColumns.stream().filter((entry) -> {
            String lowerCaseSearchTerm = searchTerm.toLowerCase(Locale.ENGLISH);
            return entry.getKey().toLowerCase(Locale.ENGLISH).contains(lowerCaseSearchTerm) || (entry.getValue().isCategorical()
                && entry.getValue().getCategoryValues().stream().map(String::toLowerCase).toList().contains(lowerCaseSearchTerm));
        }).collect(Collectors.toMap(Entry::getKey, Entry::getValue)) : allColumns;

        // Info Values
        Map<String, Map<String, Object>> infoResults = new TreeMap<>();
        if (searchTerm != null) {
            queryExecutor.getInfoStoreMeta().forEach(infoColumnMeta -> {
                String lowerCase = searchTerm.toLowerCase(Locale.ENGLISH);
                boolean storeIsNumeric = infoColumnMeta.continuous();
                if (
                    infoColumnMeta.description().toLowerCase(Locale.ENGLISH).contains(lowerCase)
                        || infoColumnMeta.key().toLowerCase(Locale.ENGLISH).contains(lowerCase)
                ) {
                    infoResults.put(
                        infoColumnMeta.key(),
                        ImmutableMap.of(
                            "description", infoColumnMeta.description(), "values",
                            storeIsNumeric ? new ArrayList<String>() : queryExecutor.searchInfoConceptValues(infoColumnMeta.key(), ""),
                            "continuous", storeIsNumeric
                        )
                    );
                }
            });
        }

        return new SearchResults(
            ImmutableMap.of("phenotypes", phenotypeResults, "info", infoResults), searchTerm != null ? searchTerm : ""
        );
    }

    /**
     * Submits a query. The body is the BARE v3 {@link Query}: there is no envelope to unwrap, and no string-encoded query to re-parse.
     */
    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    public ResponseEntity<QueryStatusResponse> query(@RequestBody Query query) {
        if (Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            try {
                QueryStatusResponse result = convertToQueryStatus(queryService.runQuery(query));
                AuditAttributes.putMetadata(httpRequest, "result_type", String.valueOf(query.expectedResultType()));
                AuditAttributes.putMetadata(httpRequest, "query_id", result.resourceResultId());
                return ResponseEntity.ok(result);
            } catch (IOException e) {
                log.error("IOException caught in query processing:", e);
                return ResponseEntity.status(500).build();
            }
        } else {
            return ResponseEntity.ok(new QueryStatusResponse(null, null, "Resource is locked.", null, 0L, 0L, 0L, 0L, Map.of()));
        }
    }

    private QueryStatusResponse convertToQueryStatus(AsyncResult entity) {
        long sizeInBytes = entity.getStatus() == AsyncResult.Status.SUCCESS ? entity.getStream().estimatedSize() : 0L;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("picsureQueryId", entity.getQuery().id());
        return new QueryStatusResponse(
            entity.getQuery().picsureId(), PicSureStatus.valueOf(entity.getStatus().toPicSureStatus().name()), entity.getStatus().name(),
            entity.getId(), sizeInBytes, entity.getQueuedTime(),
            entity.getCompletedTime() == 0 ? 0 : entity.getCompletedTime() - entity.getQueuedTime(), 0L, metadata
        );
    }

    /**
     * Streams a finished result. Bodyless: the {@code resourceQueryId} in the path is the whole request. The failure branch carries the
     * backing status as a plain-text diagnostic, so the response type stays a single {@link InputStreamResource}.
     */
    @AuditEvent(type = "DATA_ACCESS", action = "query.result")
    @PostMapping(value = "/query/{resourceQueryId}/result")
    public ResponseEntity<InputStreamResource> queryResult(@PathVariable("resourceQueryId") UUID queryId) {
        AuditAttributes.putMetadata(httpRequest, "query_id", queryId.toString());
        AsyncResult result = queryService.getResultFor(queryId);
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        if (result.getStatus() == AsyncResult.Status.SUCCESS) {
            result.open();
            return ResponseEntity.ok().contentType(result.getResponseType()).body(new InputStreamResource(result.getStream()));
        } else {
            return ResponseEntity.status(400).contentType(MediaType.TEXT_PLAIN).body(plainText("Status : " + result.getStatus().name()));
        }
    }

    private static InputStreamResource plainText(String message) {
        return new InputStreamResource(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)));
    }

    @AuditEvent(type = "DATA_ACCESS", action = "data.write")
    @PostMapping("/write/{dataType}")
    public ResponseEntity<String> writeQueryResult(@RequestBody() Query query, @PathVariable("dataType") String datatype) {
        AuditAttributes.putMetadata(httpRequest, "data_type", datatype);
        AuditAttributes.putMetadata(httpRequest, "result_type", String.valueOf(query.expectedResultType()));
        if ("test_upload".equals(datatype)) {
            return testDataService.uploadTestFile(query.picsureId().toString()) ? ResponseEntity.ok().build()
                : ResponseEntity.status(500).build();
        }
        if (!List.of(ResultType.DATAFRAME_TIMESERIES, ResultType.PATIENTS).contains(query.expectedResultType())) {
            return ResponseEntity.status(400).body("The write endpoint only writes time series dataframes. Fix result type.");
        }
        String hpdsQueryID;
        try {
            QueryStatusResponse queryStatus = convertToQueryStatus(queryService.runQuery(query));
            String status = queryStatus.resourceStatus();
            hpdsQueryID = queryStatus.resourceResultId();
            while ("RUNNING".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)) {
                Thread.sleep(10000); // Yea, this is not restful. Sorry.
                status = convertToQueryStatus(queryService.getStatusFor(hpdsQueryID)).resourceStatus();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Error waiting for response", e);
            return ResponseEntity.internalServerError().build();
        }

        AsyncResult result = queryService.getResultFor(UUID.fromString(hpdsQueryID));
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        if (AsyncResult.Status.ERROR.equals(result.getStatus())) {
            return ResponseEntity.status(500).build();
        }
        if (!AsyncResult.Status.SUCCESS.equals(result.getStatus())) {
            return ResponseEntity.status(503).build(); // 503 = unavailable
        }

        boolean success = false;
        Query queryCopy = query.generateId();
        if ("phenotypic".equals(datatype)) {
            success = fileSharingService.createPhenotypicData(queryCopy);
        } else if ("genomic".equals(datatype)) {
            success = fileSharingService.createGenomicData(queryCopy);
        } else if ("patients".equals(datatype)) {
            success = ResultType.PATIENTS.equals(queryCopy.expectedResultType()) && fileSharingService.createPatientList(queryCopy);
        }
        return success ? ResponseEntity.ok().build() : ResponseEntity.internalServerError().build();
    }

    /**
     * Hands back a time-limited download URL for a finished result. Bodyless, like {@link #queryResult}. A result that is not ready is a
     * 400 whose backing status is logged rather than returned, so the success type stays the single {@link SignedUrlResponse} contract.
     */
    @AuditEvent(type = "DATA_ACCESS", action = "query.signed.url")
    @PostMapping(value = "/query/{resourceQueryId}/signed-url")
    public ResponseEntity<SignedUrlResponse> querySignedURL(@PathVariable("resourceQueryId") UUID queryId) throws IOException {
        AuditAttributes.putMetadata(httpRequest, "query_id", queryId.toString());
        AsyncResult result = queryService.getResultFor(queryId);
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        if (result.getStatus() == AsyncResult.Status.SUCCESS) {
            File file = result.getFile();
            signUrlService.uploadFile(file, file.getName());
            String presignedGetUrl = signUrlService.createPresignedGetUrl(file.getName());
            log.info("Presigned url: " + presignedGetUrl);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new SignedUrlResponse(presignedGetUrl));
        } else {
            log.warn("Signed url requested for query {} in status {}", queryId, result.getStatus().name());
            return ResponseEntity.status(400).build();
        }
    }

    /** Bodyless GET: the {@code resourceQueryId} in the path is the whole request. */
    @AuditEvent(type = "QUERY", action = "query.status")
    @GetMapping("/query/{resourceQueryId}/status")
    public QueryStatusResponse queryStatus(@PathVariable("resourceQueryId") UUID queryId) {
        AuditAttributes.putMetadata(httpRequest, "query_id", queryId.toString());
        return convertToQueryStatus(queryService.getStatusFor(queryId.toString()));
    }

    /**
     * Runs a query and returns its result inline. Like {@link #query}, the body is the BARE v3 {@link Query}.
     *
     * <p>The response body type genuinely varies with {@code expectedResultType} -- a cross-count map, an info-column list, a CSV or VCF
     * text payload -- so {@code Object} is the honest declaration here, not an untyped leftover. The {@code @ApiResponse} annotations below
     * document that variance rather than pretending a single schema: this is the ONLY v3 endpoint whose response shape is not a named
     * contract record.
     */
    @Operation(
        summary = "Run a query and return its result inline",
        description = "The response body shape is selected by the request's expectedResultType, so this endpoint has no single response "
            + "schema. JSON object/array bodies: CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and "
            + "OBSERVATION_CROSS_COUNT return a map of concept path to counts; VARIANT_COUNT_FOR_QUERY returns a count map; "
            + "INFO_COLUMN_LISTING returns an array of InfoColumnMeta. text/plain bodies: COUNT returns a bare integer as text; "
            + "VARIANT_LIST_FOR_QUERY, VCF_EXCERPT and AGGREGATE_VCF_EXCERPT return tab-separated text; DATAFRAME, "
            + "SECRET_ADMIN_DATAFRAME, DATAFRAME_TIMESERIES and PATIENTS block until the async result completes and then return the "
            + "CSV payload. Successful responses also carry the queryMetadata response header."
    )
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200", description = "The result, in the media type the expectedResultType implies (see the description).",
            content = {@Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                // type MUST be stated. A @Schema carrying only a description makes springdoc fall back to
                // type: string, which would document a CROSS_COUNT map as a JSON string -- a precise lie, and
                // worse for a client than saying nothing. additionalProperties keeps the map open, since the
                // keys are concept paths and are not enumerable here.
                schema = @Schema(
                    type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
                    description = "A JSON object keyed by concept path: the cross-count maps (CROSS_COUNT, "
                        + "CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT, OBSERVATION_CROSS_COUNT) and the "
                        + "VARIANT_COUNT_FOR_QUERY count map. INFO_COLUMN_LISTING is the one exception on this "
                        + "media type: it returns a JSON ARRAY of InfoColumnMeta rather than an object."
                )
            ), @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string", description = "Count, variant list, VCF excerpt or CSV dataframe, per expectedResultType"))}
        ), @ApiResponse(responseCode = "400", description = "The async result backing a DATAFRAME-family query did not succeed; the body is the " + "backing status as a diagnostic string.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)), @ApiResponse(responseCode = "403", description = "The resource is locked (no encryption key loaded).", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE)), @ApiResponse(responseCode = "500", description = "The expectedResultType is not one this endpoint serves, or the result could not be read.", content = @Content)}
    )
    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping(value = "/query/sync", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<Object> querySync(@RequestBody Query query) {
        if (Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            try {
                return _querySync(query);
            } catch (IOException e) {
                log.error("IOException  caught: ", e);
                return ResponseEntity.status(500).build();
            }
        } else {
            return ResponseEntity.status(403).body("Resource is locked");
        }
    }

    /**
     * Paged genomic concept values. <b>Paging here is 1-based</b>, unlike the dictionary's {@code /concepts} endpoints, which are 0-based:
     * this endpoint is served by the legacy {@link Paginator} and rejects {@code page < 1}. {@code PaginatedResponse.page} echoes whichever
     * base the serving endpoint uses, so a client must not assume one across services.
     */
    @Operation(
        summary = "Search genomic concept values",
        description = "Paging is 1-based on this endpoint: page 1 is the first page and page < 1 is rejected."
    )
    @AuditEvent(type = "SEARCH", action = "search.values")
    @GetMapping("/search/values/")
    public PaginatedResponse<String> searchGenomicConceptValues(
        @RequestParam("genomicConceptPath") String genomicConceptPath, @RequestParam("query") String query,
        @Parameter(
            description = "ONE-BASED page index. The first page is 1; a value below 1 is a 400. The dictionary's /concepts "
                + "endpoints page from 0 -- these two surfaces do not share a base.",
            schema = @Schema(type = "integer", format = "int32", minimum = "1", example = "1")
        ) @RequestParam("page") int page,
        @Parameter(
            description = "Page size; must be at least 1.", schema = @Schema(type = "integer", format = "int32", minimum = "1")
        ) @RequestParam("size") int size
    ) {
        AuditAttributes.putMetadata(httpRequest, "genomic_concept_path", genomicConceptPath);
        if (page < 1) {
            throw new IllegalArgumentException("Page must be greater than 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Size must be greater than 0");
        }
        final List<String> matchingValues = queryExecutor.searchInfoConceptValues(genomicConceptPath, query);
        return paginator.page(matchingValues, page, size);
    }

    private ResponseEntity<Object> _querySync(Query incomingQuery) throws IOException {
        AuditAttributes.putMetadata(httpRequest, "result_type", String.valueOf(incomingQuery.expectedResultType()));
        switch (incomingQuery.expectedResultType()) {

            case INFO_COLUMN_LISTING:
                List<InfoColumnMeta> infoColumnMeta = queryExecutor.getInfoStoreMeta();
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(infoColumnMeta);

            case DATAFRAME:
            case SECRET_ADMIN_DATAFRAME:
            case DATAFRAME_TIMESERIES:
            case PATIENTS:
                QueryStatusResponse status = query(incomingQuery).getBody();
                while (status.resourceStatus().equalsIgnoreCase("RUNNING") || status.resourceStatus().equalsIgnoreCase("PENDING")) {
                    status = queryStatus(UUID.fromString(status.resourceResultId()));
                }
                log.info(status.toString());

                AsyncResult result = queryService.getResultFor(UUID.fromString(status.resourceResultId()));
                if (result.getStatus() == AsyncResult.Status.SUCCESS) {
                    result.getStream().open();
                    return queryOkResponse(
                        new String(result.getStream().readAllBytes(), StandardCharsets.UTF_8), incomingQuery, MediaType.TEXT_PLAIN
                    );
                }
                return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON).body("Status : " + result.getStatus().name());

            case CROSS_COUNT:
                return queryOkResponse(countProcessor.runCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

            case CATEGORICAL_CROSS_COUNT:
                return queryOkResponse(countProcessor.runCategoryCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

            case CONTINUOUS_CROSS_COUNT:
                return queryOkResponse(countProcessor.runContinuousCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

            case OBSERVATION_CROSS_COUNT:
                return queryOkResponse(countProcessor.runObservationCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

            case VARIANT_COUNT_FOR_QUERY:
                return queryOkResponse(countProcessor.runVariantCount(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

            case VARIANT_LIST_FOR_QUERY:
                return queryOkResponse(variantListProcessor.runVariantListQuery(incomingQuery), incomingQuery, MediaType.TEXT_PLAIN);

            case VCF_EXCERPT:
                return queryOkResponse(variantListProcessor.runVcfExcerptQuery(incomingQuery, true), incomingQuery, MediaType.TEXT_PLAIN);

            case AGGREGATE_VCF_EXCERPT:
                return queryOkResponse(variantListProcessor.runVcfExcerptQuery(incomingQuery, false), incomingQuery, MediaType.TEXT_PLAIN);

            case COUNT:
                return queryOkResponse(String.valueOf(countProcessor.runCounts(incomingQuery)), incomingQuery, MediaType.TEXT_PLAIN);

            default:
                // no valid type
                return ResponseEntity.status(500).build();
        }
    }

    private ResponseEntity<Object> queryOkResponse(Object obj, Query incomingQuery, MediaType mediaType) {
        Query query = incomingQuery.generateId();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set(QUERY_METADATA_FIELD, UUIDv5.UUIDFromString(query.toString()).toString());
        return ResponseEntity.ok().contentType(mediaType).headers(responseHeaders).body(obj);
    }
}
