package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import edu.harvard.dbmi.avillach.domain.FederatedQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

/**
 * Ports the legacy WAR's {@code PicsureRS} (v1) query lifecycle: {@code /hpds/{backend}/query/**}. {@code {backend}} is {@code auth} or
 * {@code open} (validated downstream by {@link QueryService}, via {@code HpdsBackendSelector}). Only create ({@code /query}) and sync
 * ({@code /query/sync}) are version-specific; the read ops (status/result/signed-url/metadata) are byte-for-byte identical to
 * {@link HpdsQueryV3Controller}'s because they delegate to the version-agnostic {@link QueryService} methods that dispatch HPDS-side on the
 * STORED query's version, never the ingress path (decision 9).
 */
@RestController
@RequestMapping("/hpds/{backend}")
public class HpdsQueryV1Controller {

    private final QueryService service;

    public HpdsQueryV1Controller(QueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public QueryStatus query(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestParam(name = "isInstitute", required = false) Boolean isInstitute, GatewayUser user
    ) {
        if (Boolean.TRUE.equals(isInstitute)) {
            if (!(req instanceof FederatedQueryRequest federatedReq)) {
                throw new PicsureException(
                    HttpStatus.BAD_REQUEST, "invalid_request",
                    "An institutional (isInstitute=true) query requires a federated query request body"
                );
            }
            return service.institutionalQuery(backend, federatedReq, requireEmail(user), false);
        }
        return service.query(backend, req);
    }

    @PostMapping("/query/sync")
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestHeader(name = "request-source", required = false) String requestSource
    ) {
        return syncResponse(service.querySync(backend, req, requestSource, false));
    }

    @PostMapping("/query/{id}/status")
    public QueryStatus status(@PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req) {
        return service.queryStatus(backend, id, req);
    }

    @PostMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> result(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req
    ) {
        return service.queryResult(backend, id, req);
    }

    @PostMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> signedUrl(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req
    ) {
        return service.queryResultSignedUrl(backend, id, req);
    }

    @GetMapping("/query/{id}/metadata")
    public QueryStatus metadata(@PathVariable("backend") String backend, @PathVariable("id") UUID id) {
        return service.queryMetadata(id); // DB-only, backend irrelevant
    }

    private static String requireEmail(GatewayUser user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "An institutional query requires a caller email");
        }
        return user.getEmail();
    }

    /**
     * Re-emits the {@code queryMetadata} response header like the WAR's querySync, as octet-stream. Shared with
     * {@link HpdsQueryV3Controller}.
     */
    static ResponseEntity<byte[]> syncResponse(QueryService.QuerySyncResponse r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (r.queryMetadata() != null) {
            b.header("queryMetadata", r.queryMetadata());
        }
        return b.body(r.body());
    }
}
