package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The sole HPDS query lifecycle ingress: {@code /hpds/{backend}/query/**}. {@code {backend}} is {@code auth} or {@code open}, validated
 * downstream by {@link QueryService} through {@code HpdsBackendSelector}. Every query runs on HPDS v3 and is stored as version {@code "3"}.
 * A status, result, or signed-url read of a row stored before v3 first upgrades that row in place (translated, re-scoped by the caller's
 * consents, and re-run), and answers 422 when the stored query cannot be translated.
 *
 * <p>Every body binds {@link HpdsQueryRequest}, a v3 {@code Query} under the query key. Each handler passes {@link QueryService} the
 * outbound envelope built by {@link HpdsQueryRequest#toOutbound()}, which is what HPDS receives and what the operations service stores.
 */
@RestController
@RequestMapping("/hpds/{backend}")
@Tag(name = "Queries", description = "Run, poll, and fetch HPDS queries on the auth or open backend.")
public class HpdsQueryController {

    private static final String UNREADABLE_BODY = " or a body that cannot be read as a query request.";

    private static final String COUNT_EXAMPLE = "1234";

    private static final String CROSS_COUNT_EXAMPLE = "{\"\\\\demographics\\\\SEX\\\\\":1234}";

    private static final String CATEGORICAL_CROSS_COUNT_EXAMPLE = "{\"\\\\demographics\\\\SEX\\\\\":{\"Female\":634,\"Male\":600}}";

    private static final String CONTINUOUS_CROSS_COUNT_EXAMPLE = "{\"\\\\demographics\\\\AGE\\\\\":{\"42.0\":17,\"43.0\":12}}";

    private static final String OBSERVATION_CROSS_COUNT_EXAMPLE = "{\"\\\\demographics\\\\SEX\\\\\":4321}";

    private static final String INFO_COLUMN_LISTING_EXAMPLE =
        "[{\"key\":\"Gene_with_variant\",\"description\":\"The official symbol for a gene affected by a variant.\","
            + "\"continuous\":false,\"min\":null,\"max\":null}]";

    private static final String VARIANT_LIST_EXAMPLE = "[19,44908684,T,C,APOE,missense_variant, 19,44908822,C,T,APOE,missense_variant]";

    private static final String VCF_EXCERPT_EXAMPLE =
        "CHROM\tPOSITION\tREF\tALT\tPatients with this variant in subset\tPatients with this variant NOT in subset\n"
            + "19\t44908684\tT\tC\t12/1234\t3/4000\n";

    private final QueryService service;

    public HpdsQueryController(QueryService service) {
        this.service = service;
    }

    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    @Operation(summary = "Submit an asynchronous query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The status of the submitted query."),
            @ApiResponse(responseCode = "400", description = "Unknown backend, missing query data," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent does not permit this query."),
            @ApiResponse(responseCode = "410", description = "Institutional (federated) queries are no longer supported."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public QueryStatus query(
        @PathVariable("backend") String backend, @RequestBody HpdsQueryRequest req,
        @RequestParam(name = "isInstitute", required = false) Boolean isInstitute,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        rejectInstitutionalQuery(isInstitute);
        return service.query(backend, req.toOutbound(), authorizationHeader);
    }

    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping("/query/sync")
    @Operation(summary = "Run a query and return its result inline")
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200",
            description = "The result. Its shape follows the query's expectedResultType, and the body is labelled application/json for "
                + "every result type, the plain-text ones included. DATAFRAME, DATAFRAME_TIMESERIES, DATAFRAME_PFB and PATIENTS are not "
                + "served here and answer 400: submit them with POST /query and collect them from /query/{id}/result.",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(
                    type = "string",
                    description = "COUNT: a bare patient count as text. CROSS_COUNT and OBSERVATION_CROSS_COUNT: a JSON object of concept "
                        + "path to integer count. CATEGORICAL_CROSS_COUNT and CONTINUOUS_CROSS_COUNT: a JSON object of concept path to an "
                        + "object of value to integer count. INFO_COLUMN_LISTING: a JSON array of variant annotation columns. "
                        + "VARIANT_COUNT_FOR_QUERY: a JSON object with count and message, where count is an integer when the query has "
                        + "genomic filters and the string \"0\" when it has none. VARIANT_LIST_FOR_QUERY: a bracketed, comma-separated "
                        + "list of variants as text. VCF_EXCERPT and AGGREGATE_VCF_EXCERPT: tab-separated text with a header row."
                ),
                examples = {@ExampleObject(name = "COUNT", value = COUNT_EXAMPLE),
                    @ExampleObject(name = "CROSS_COUNT", value = CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "CATEGORICAL_CROSS_COUNT", value = CATEGORICAL_CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "CONTINUOUS_CROSS_COUNT", value = CONTINUOUS_CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "OBSERVATION_CROSS_COUNT", value = OBSERVATION_CROSS_COUNT_EXAMPLE),
                    @ExampleObject(name = "INFO_COLUMN_LISTING", value = INFO_COLUMN_LISTING_EXAMPLE),
                    @ExampleObject(
                        name = "VARIANT_COUNT_FOR_QUERY with genomic filters", value = SyncExamples.VARIANT_COUNT_WITH_GENOMIC_FILTERS
                    ),
                    @ExampleObject(
                        name = "VARIANT_COUNT_FOR_QUERY without genomic filters", value = SyncExamples.VARIANT_COUNT_WITHOUT_GENOMIC_FILTERS
                    ), @ExampleObject(name = "VARIANT_LIST_FOR_QUERY", value = VARIANT_LIST_EXAMPLE),
                    @ExampleObject(name = "VCF_EXCERPT", value = VCF_EXCERPT_EXAMPLE)}
            )
        ), @ApiResponse(responseCode = "400", description = "Unknown backend, missing query data, a result type served asynchronously," + UNREADABLE_BODY), @ApiResponse(responseCode = "403", description = "Consent does not permit this query."), @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed."), @ApiResponse(responseCode = "503", description = "Backend not configured."), @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody HpdsQueryRequest req,
        @RequestHeader(name = "request-source", required = false) String requestSource,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return syncResponse(service.querySync(backend, req.toOutbound(), requestSource, authorizationHeader));
    }

    @AuditEvent(type = "QUERY", action = "query.status")
    @PostMapping("/query/{id}/status")
    @Operation(summary = "Status of a submitted query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The status of the query."),
            @ApiResponse(responseCode = "400", description = "Unknown backend," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent does not permit re-running a query stored before v3."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, HPDS call, or status update failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public QueryStatus status(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody HpdsQueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryStatus(backend, id, req.toOutbound(), authorizationHeader);
    }

    @AuditEvent(type = "DATA_ACCESS", action = "query.result")
    @PostMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Result bytes of a completed query")
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200",
            description = "The result file, labelled with the content type HPDS gave it: text/plain for the CSV of DATAFRAME and "
                + "DATAFRAME_TIMESERIES, application/octet-stream for the Avro PFB of DATAFRAME_PFB.",
            content = {
                @Content(
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary", description = "The Avro PFB file of a DATAFRAME_PFB query.")
                ),
                @Content(
                    mediaType = MediaType.TEXT_PLAIN_VALUE,
                    schema = @Schema(
                        type = "string",
                        description = "The CSV of a DATAFRAME or DATAFRAME_TIMESERIES query: a header row, then one row per patient."
                    )
                )}
        ), @ApiResponse(responseCode = "400", description = "Unknown backend, a result that is not ready yet," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public ResponseEntity<byte[]> result(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody HpdsQueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResult(backend, id, req.toOutbound(), authorizationHeader);
    }

    @AuditEvent(type = "DATA_ACCESS", action = "query.signed_url")
    @PostMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A signed URL for a completed query's result")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The signed URL for the completed query's result."),
            @ApiResponse(
                responseCode = "400", description = "Unknown backend, a body that cannot be read, or a result that is not ready yet."
            ), @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public SignedUrlResponse signedUrl(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody HpdsQueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResultSignedUrl(backend, id, req.toOutbound(), authorizationHeader);
    }

    @AuditEvent(type = "QUERY", action = "query.metadata")
    @RequestMapping(path = "/query/{id}/metadata", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "Metadata of a submitted query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The metadata of the submitted query."),
            @ApiResponse(responseCode = "400", description = "Missing query id."),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "502", description = "Consent or query lookup failed."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public QueryStatus metadata(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryMetadata(backend, id, authorizationHeader);
    }

    /**
     * Federated/GIC queries were removed. The parameter stays bound on purpose: a body that still carries
     * {@code "@type":"FederatedQueryRequest"} binds as an ordinary {@link HpdsQueryRequest}, because the envelope ignores members it does
     * not know, so this flag is the only surviving signal of federated intent. Accepting it would return 200 for a query whose federation
     * had been quietly discarded.
     */
    static void rejectInstitutionalQuery(Boolean isInstitute) {
        if (Boolean.TRUE.equals(isInstitute)) {
            throw new PicsureException(HttpStatus.GONE, "gone", "Institutional (federated) queries are no longer supported");
        }
    }

    /**
     * Re-emits the {@code queryMetadata} response header with an {@code application/json} content type. Only {@code /query/{id}/result}
     * uses octet-stream; labeling sync responses as octet-stream makes the frontend treat count results as binary downloads.
     */
    static ResponseEntity<byte[]> syncResponse(QueryService.QuerySyncResponse r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (r.queryMetadata() != null) {
            b.header("queryMetadata", r.queryMetadata());
        }
        return b.body(r.body());
    }
}
