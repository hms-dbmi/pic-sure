package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * <p>Submit and sync bind their body to {@link HpdsQueryRequest}, a v3 {@code Query} under the query key, and pass {@link QueryService} the
 * outbound envelope built by {@link HpdsQueryRequest#toOutbound()}, which is what HPDS receives and what the operations service stores.
 * Status, result, signed-url and metadata are GET requests with no body: they read the stored query by id, and a POST to them answers 405.
 */
@RestController
@RequestMapping("/hpds/{backend}")
@Tag(name = "Queries", description = HpdsQueryController.TAG_DESCRIPTION)
public class HpdsQueryController {

    static final String TAG_DESCRIPTION = "Run, poll, and fetch queries on the authorized HPDS backend. Results are limited to the studies "
        + "the caller is consented to. Open access, where a deployment enables it, has its own document.";

    private static final String BACKEND =
        "`auth` or `open`. `open` answers only where the environment enables open HPDS, and serves no genomic data.";

    private static final String CURL_TEMPLATE =
        "\n\nPick a request body under **Examples** to fill the body, then **Execute**. Each example also "
            + "runs from a shell; paste it in place of `<example>`:\n\n";

    private static final String SUBMIT_DESCRIPTION = """
        Queues a participant-level query and returns right away. Collecting the result takes three requests that share one id:

        1. **Submit** this request. The response's `picsureResultId` is the `{id}` for the next two steps.
        2. **Poll** `GET /hpds/auth/query/{id}/status` until `status` is `AVAILABLE`. `QUEUED` and `PENDING` mean the query is \
        still waiting or running. `ERROR` means it failed and will not produce a result.
        3. **Download** `GET /hpds/auth/query/{id}/result`, or call `GET /hpds/auth/query/{id}/signed-url` for a download link.

        In Swagger UI, run this request, then open **Status of a submitted query** and **Result bytes of a completed query** \
        below and paste `picsureResultId` into `id` on each.

        **Result types**: PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES and DATAFRAME_PFB. Counts, cross counts and the genomic \
        result types run on `POST /hpds/auth/query/sync`.

        Pick a request body under **Examples** to fill the body, then **Execute**. From a shell with `jq` installed, paste an \
        example in place of `<example>`:

            ID=$(curl -s -X POST "https://<host>/picsure/hpds/auth/query" \\
              -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \\
              -d '<example>' | jq -r .picsureResultId)
            curl -s "https://<host>/picsure/hpds/auth/query/$ID/status" -H "Authorization: Bearer $TOKEN" | jq -r .status
            curl -s "https://<host>/picsure/hpds/auth/query/$ID/result" -H "Authorization: Bearer $TOKEN" -o result.csv

        Repeat the status call until it prints `AVAILABLE`, then run the last line. For DATAFRAME_PFB, save to `result.avro`.
        """;

    private static final String STATUS_DESCRIPTION = """
        Step 2 of collecting an asynchronous query: poll this until the query finishes. `{id}` is the `picsureResultId` that \
        `POST /hpds/auth/query` returned.

        | `status` | Meaning | Next step |
        |---|---|---|
        | `QUEUED` | Accepted and waiting for a worker | Poll again |
        | `PENDING` | Running | Poll again |
        | `AVAILABLE` | Finished; `sizeInBytes` is the result's size | Fetch `GET /hpds/auth/query/{id}/result` |
        | `ERROR` | Failed | Stop polling. This query will not produce a result; fix it and submit it again |

        Poll every few seconds at first, backing off to about every 10 seconds for a long-running query.
        """;

    private static final String RESULT_DESCRIPTION = """
        Step 3 of collecting an asynchronous query: download the result once `GET /hpds/auth/query/{id}/status` reports \
        `AVAILABLE`. Before that, this answers 400. `{id}` is the `picsureResultId` that `POST /hpds/auth/query` returned.

        | Result type | Body |
        |---|---|
        | PATIENTS | CSV as `text/plain`: a header row, then one patient id per line |
        | DATAFRAME, DATAFRAME_TIMESERIES | CSV as `text/plain`: a header row, then the data rows |
        | DATAFRAME_PFB | An Avro PFB file as `application/octet-stream` |

        Swagger UI offers the body as a download. For a large result, `GET /hpds/auth/query/{id}/signed-url` returns a link to \
        the same file instead.
        """;

    private static final String SIGNED_URL_DESCRIPTION = """
        An alternative to step 3 of collecting an asynchronous query. Once `GET /hpds/auth/query/{id}/status` reports \
        `AVAILABLE`, this copies the result to the deployment's storage bucket and returns a pre-signed download link in \
        `signedUrl`. Before that, this answers 400.

        Download the link with any HTTP client and without the PIC-SURE token: the link carries its own authorization, and \
        storage rejects a request that sends both.
        """;

    private static final String AUTH_ONLY_READ = "Reads a query submitted with `POST /hpds/auth/query`.";

    private static final String UNREADABLE_BODY = " or a body that cannot be read as a query request.";

    private static final String METHOD_NOT_ALLOWED =
        "The request used a method this endpoint does not support, such as POST. The Allow header names GET.";

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
    @Operation(summary = "Submit an asynchronous query", description = SUBMIT_DESCRIPTION)
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
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = HpdsQueryRequest.class),
                examples = {
                    @ExampleObject(
                        name = "PATIENTS", description = QueryRequestExamples.PATIENTS_DESCRIPTION, value = QueryRequestExamples.PATIENTS
                    ),
                    @ExampleObject(
                        name = "DATAFRAME", description = QueryRequestExamples.DATAFRAME_DESCRIPTION, value = QueryRequestExamples.DATAFRAME
                    ),
                    @ExampleObject(
                        name = "DATAFRAME_TIMESERIES", description = QueryRequestExamples.DATAFRAME_TIMESERIES_DESCRIPTION,
                        value = QueryRequestExamples.DATAFRAME_TIMESERIES
                    ),
                    @ExampleObject(
                        name = "DATAFRAME_PFB", description = QueryRequestExamples.DATAFRAME_PFB_DESCRIPTION,
                        value = QueryRequestExamples.DATAFRAME_PFB
                    )}
            )
        ) @RequestBody HpdsQueryRequest req, @RequestParam(name = "isInstitute", required = false) Boolean isInstitute,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        rejectInstitutionalQuery(isInstitute);
        return service.query(backend, req.toOutbound(), authorizationHeader);
    }

    @AuditEvent(type = "QUERY", action = "query.sync")
    @PostMapping("/query/sync")
    @Operation(
        summary = "Run a query and return its result inline",
        description = "Runs the query and returns its result in the response.\n\n**Result types**: COUNT, CROSS_COUNT, "
            + "CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT, and the genomic types INFO_COLUMN_LISTING, "
            + "VARIANT_COUNT_FOR_QUERY, VARIANT_LIST_FOR_QUERY, VCF_EXCERPT and AGGREGATE_VCF_EXCERPT. PATIENTS and the DATAFRAME "
            + "types are not served here; submit them with `POST /hpds/auth/query`." + CURL_TEMPLATE
            + "    curl -X POST \"https://<host>/picsure/hpds/auth/query/sync\" \\\n"
            + "      -H \"Authorization: Bearer $TOKEN\" -H \"Content-Type: application/json\" \\\n      -d '<example>'\n"
    )
    @ApiResponses(
        {@ApiResponse(
            responseCode = "200",
            description = "The result. Its shape follows the query's expectedResultType, and the body is labelled application/json for "
                + "every result type, the plain-text ones included.",
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
        ), @ApiResponse(responseCode = "400", description = "Unknown backend, missing query data, a result type not served inline," + UNREADABLE_BODY), @ApiResponse(responseCode = "403", description = "Consent does not permit this query."), @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed."), @ApiResponse(responseCode = "503", description = "Backend not configured."), @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public ResponseEntity<byte[]> querySync(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = HpdsQueryRequest.class),
                examples = {
                    @ExampleObject(
                        name = "COUNT", description = QueryRequestExamples.COUNT_DESCRIPTION, value = QueryRequestExamples.COUNT
                    ),
                    @ExampleObject(
                        name = "CROSS_COUNT", description = QueryRequestExamples.CROSS_COUNT_DESCRIPTION,
                        value = QueryRequestExamples.CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "CATEGORICAL_CROSS_COUNT", description = QueryRequestExamples.CATEGORICAL_CROSS_COUNT_DESCRIPTION,
                        value = QueryRequestExamples.CATEGORICAL_CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "CONTINUOUS_CROSS_COUNT", description = QueryRequestExamples.CONTINUOUS_CROSS_COUNT_DESCRIPTION,
                        value = QueryRequestExamples.CONTINUOUS_CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "OBSERVATION_CROSS_COUNT", description = QueryRequestExamples.OBSERVATION_CROSS_COUNT_DESCRIPTION,
                        value = QueryRequestExamples.OBSERVATION_CROSS_COUNT
                    ),
                    @ExampleObject(
                        name = "INFO_COLUMN_LISTING", description = QueryRequestExamples.INFO_COLUMN_LISTING_DESCRIPTION,
                        value = QueryRequestExamples.INFO_COLUMN_LISTING
                    ),
                    @ExampleObject(
                        name = "VARIANT_COUNT_FOR_QUERY", description = QueryRequestExamples.VARIANT_COUNT_FOR_QUERY_DESCRIPTION,
                        value = QueryRequestExamples.VARIANT_COUNT_FOR_QUERY
                    ),
                    @ExampleObject(
                        name = "VARIANT_LIST_FOR_QUERY", description = QueryRequestExamples.VARIANT_LIST_FOR_QUERY_DESCRIPTION,
                        value = QueryRequestExamples.VARIANT_LIST_FOR_QUERY
                    ),
                    @ExampleObject(
                        name = "VCF_EXCERPT", description = QueryRequestExamples.VCF_EXCERPT_DESCRIPTION,
                        value = QueryRequestExamples.VCF_EXCERPT
                    ),
                    @ExampleObject(
                        name = "AGGREGATE_VCF_EXCERPT", description = QueryRequestExamples.AGGREGATE_VCF_EXCERPT_DESCRIPTION,
                        value = QueryRequestExamples.AGGREGATE_VCF_EXCERPT
                    )}
            )
        ) @RequestBody HpdsQueryRequest req, @RequestHeader(name = "request-source", required = false) String requestSource,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return syncResponse(service.querySync(backend, req.toOutbound(), requestSource, authorizationHeader));
    }

    @AuditEvent(type = "QUERY", action = "query.status")
    @GetMapping("/query/{id}/status")
    @Operation(summary = "Status of a submitted query", description = STATUS_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The status of the query."),
            @ApiResponse(responseCode = "400", description = "Unknown backend."),
            @ApiResponse(responseCode = "403", description = "Consent does not permit re-running a query stored before v3."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "405", description = METHOD_NOT_ALLOWED),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, HPDS call, or status update failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public QueryStatus status(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend, @PathVariable("id") UUID id,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryStatus(backend, id, authorizationHeader);
    }

    @AuditEvent(type = "DATA_ACCESS", action = "query.result")
    @GetMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Result bytes of a completed query", description = RESULT_DESCRIPTION)
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
        ), @ApiResponse(responseCode = "400", description = "Unknown backend or a result that is not ready yet."),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "405", description = METHOD_NOT_ALLOWED),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public ResponseEntity<byte[]> result(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend, @PathVariable("id") UUID id,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResult(backend, id, authorizationHeader);
    }

    @AuditEvent(type = "DATA_ACCESS", action = "query.signed_url")
    @GetMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A signed URL for a completed query's result", description = SIGNED_URL_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The signed URL for the completed query's result."),
            @ApiResponse(responseCode = "400", description = "Unknown backend or a result that is not ready yet."),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "405", description = METHOD_NOT_ALLOWED),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3."),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed."),
            @ApiResponse(responseCode = "503", description = "Backend not configured."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public SignedUrlResponse signedUrl(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend, @PathVariable("id") UUID id,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResultSignedUrl(backend, id, authorizationHeader);
    }

    @AuditEvent(type = "QUERY", action = "query.metadata")
    @GetMapping("/query/{id}/metadata")
    @Operation(summary = "Metadata of a submitted query", description = AUTH_ONLY_READ)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The metadata of the submitted query."),
            @ApiResponse(responseCode = "400", description = "Missing query id."),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result."),
            @ApiResponse(responseCode = "404", description = "Unknown query id."),
            @ApiResponse(responseCode = "405", description = METHOD_NOT_ALLOWED),
            @ApiResponse(responseCode = "502", description = "Consent or query lookup failed."),
            @ApiResponse(responseCode = "504", description = "The operations service could not be reached or did not answer in time.")}
    )
    public QueryStatus metadata(
        @Parameter(description = BACKEND, example = "auth", schema = @Schema(allowableValues = {"auth", "open"})) @PathVariable(
            "backend"
        ) String backend, @PathVariable("id") UUID id,
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
