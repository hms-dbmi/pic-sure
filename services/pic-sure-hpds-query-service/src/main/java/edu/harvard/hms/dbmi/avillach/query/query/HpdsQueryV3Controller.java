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
 * Ports the legacy WAR's {@code PicsureRSv3} query lifecycle: {@code /hpds/{backend}/v3/query/**}. Only create and sync differ from
 * {@link HpdsQueryV1Controller} -- they stamp version {@code "3"} and dispatch to HPDS's {@code /v3} base. The read ops below are IDENTICAL
 * to v1's: both delegate to the same version-agnostic {@link QueryService} methods, which dispatch HPDS-side on the STORED query's version
 * (decision 9) rather than on which ingress path (v1 or v3) was used to reach them.
 */
@RestController
@RequestMapping("/hpds/{backend}/v3")
public class HpdsQueryV3Controller {

    private final QueryService service;

    public HpdsQueryV3Controller(QueryService service) {
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
            return service.institutionalQuery(backend, federatedReq, requireEmail(user), true);
        }
        return service.queryV3(backend, req);
    }

    @PostMapping("/query/sync")
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestHeader(name = "request-source", required = false) String requestSource
    ) {
        return HpdsQueryV1Controller.syncResponse(service.querySync(backend, req, requestSource, true));
    }

    // Read ops: identical to v1 -- stored-version dispatch makes the ingress version irrelevant (decision 9).

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
        return service.queryMetadata(id);
    }

    private static String requireEmail(GatewayUser user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "An institutional query requires a caller email");
        }
        return user.getEmail();
    }
}
