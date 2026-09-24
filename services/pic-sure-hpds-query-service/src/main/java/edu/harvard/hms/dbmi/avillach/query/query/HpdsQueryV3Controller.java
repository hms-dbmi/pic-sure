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

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The sole HPDS query lifecycle ingress: {@code /hpds/{backend}/v3/query/**}. {@code {backend}} is {@code auth} or {@code open}, validated
 * downstream by {@link QueryService} through {@code HpdsBackendSelector}. New queries are stored as version {@code "3"}; read operations
 * dispatch to HPDS using the stored query version so v1 rows remain retrievable.
 */
@RestController
@RequestMapping("/hpds/{backend}/v3")
@Tag(name = "Queries", description = "Run, poll, and fetch HPDS queries on the auth or open backend")
public class HpdsQueryV3Controller {

    private final QueryService service;

    public HpdsQueryV3Controller(QueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    @Operation(summary = "Submit an asynchronous query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend or missing query data"),
            @ApiResponse(responseCode = "403", description = "Consent does not permit this query"),
            @ApiResponse(responseCode = "410", description = "Institutional (federated) queries are no longer supported"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public QueryStatus query(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestParam(name = "isInstitute", required = false) Boolean isInstitute,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        rejectInstitutionalQuery(isInstitute);
        return service.queryV3(backend, req, authorizationHeader);
    }

    @PostMapping("/query/sync")
    @Operation(summary = "Run a query and return its result inline")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend or missing query data"),
            @ApiResponse(responseCode = "403", description = "Consent does not permit this query"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestHeader(name = "request-source", required = false) String requestSource,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return syncResponse(service.querySync(backend, req, requestSource, authorizationHeader));
    }

    @PostMapping("/query/{id}/status")
    @Operation(summary = "Status of a submitted query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Unknown backend"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "502", description = "Query lookup, HPDS call, or status update failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public QueryStatus status(@PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req) {
        return service.queryStatus(backend, id, req);
    }

    @PostMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Result bytes of a completed query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Unknown backend"),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public ResponseEntity<byte[]> result(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResult(backend, id, req, authorizationHeader);
    }

    @PostMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A signed URL for a completed query's result")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Unknown backend"),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public ResponseEntity<String> signedUrl(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResultSignedUrl(backend, id, req, authorizationHeader);
    }

    @RequestMapping(path = "/query/{id}/metadata", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "Metadata of a submitted query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"), @ApiResponse(responseCode = "400", description = "Missing query id"),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "502", description = "Consent or query lookup failed"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
    )
    public QueryStatus metadata(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryMetadata(backend, id, authorizationHeader);
    }

    /**
     * Federated/GIC queries were removed. The parameter stays bound on purpose: {@code QueryRequest}'s {@code defaultImpl} silently
     * reinterprets a {@code "@type":"FederatedQueryRequest"} body as a {@code GeneralQueryRequest}, so this flag is the only surviving
     * signal of federated intent. Accepting it would return 200 for a query whose federation had been quietly discarded.
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
