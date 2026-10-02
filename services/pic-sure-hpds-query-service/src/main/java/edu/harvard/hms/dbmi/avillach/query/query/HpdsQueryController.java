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
@Tag(name = "Queries", description = "Run, poll, and fetch HPDS queries on the auth or open backend")
public class HpdsQueryController {

    private static final String UNREADABLE_BODY = " or a body that cannot be read as a query request";

    private final QueryService service;

    public HpdsQueryController(QueryService service) {
        this.service = service;
    }

    @AuditEvent(type = "QUERY", action = "query.submitted")
    @PostMapping("/query")
    @Operation(summary = "Submit an asynchronous query")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend, missing query data," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent does not permit this query"),
            @ApiResponse(responseCode = "410", description = "Institutional (federated) queries are no longer supported"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
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
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend, missing query data," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent does not permit this query"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, HPDS call, or query save failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
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
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent does not permit re-running a query stored before v3"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, HPDS call, or status update failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
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
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
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
        {@ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Unknown backend," + UNREADABLE_BODY),
            @ApiResponse(responseCode = "403", description = "Consent no longer covers this result"),
            @ApiResponse(responseCode = "404", description = "Unknown query id"),
            @ApiResponse(responseCode = "422", description = "Query stored before v3 cannot be converted to v3"),
            @ApiResponse(responseCode = "502", description = "Consent lookup, query lookup, or HPDS call failed"),
            @ApiResponse(responseCode = "503", description = "Backend not configured"),
            @ApiResponse(responseCode = "504", description = "operations-service timed out")}
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
