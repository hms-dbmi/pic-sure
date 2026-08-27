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

/**
 * The sole HPDS query lifecycle ingress: {@code /hpds/{backend}/v3/query/**}. {@code {backend}} is {@code auth} or {@code open} (validated
 * downstream by {@link QueryService} via {@code HpdsBackendSelector}). The legacy v1 ingress ({@code /hpds/{backend}/query/**}) was
 * removed; new queries are always stored as version {@code "3"}. Read ops still dispatch HPDS-side on the STORED query's version, so
 * previously stored v1 rows remain retrievable via their v1 routes.
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
        @RequestParam(name = "isInstitute", required = false) Boolean isInstitute,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        rejectInstitutionalQuery(isInstitute);
        return service.queryV3(backend, req, authorizationHeader);
    }

    @PostMapping("/query/sync")
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody QueryRequest req,
        @RequestHeader(name = "request-source", required = false) String requestSource,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return syncResponse(service.querySync(backend, req, requestSource, authorizationHeader));
    }

    @PostMapping("/query/{id}/status")
    public QueryStatus status(@PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req) {
        return service.queryStatus(backend, id, req);
    }

    @PostMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> result(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResult(backend, id, req, authorizationHeader);
    }

    @PostMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> signedUrl(
        @PathVariable("backend") String backend, @PathVariable("id") UUID id, @RequestBody QueryRequest req,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return service.queryResultSignedUrl(backend, id, req, authorizationHeader);
    }

    @RequestMapping(path = "/query/{id}/metadata", method = {RequestMethod.GET, RequestMethod.POST})
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
     * Re-emits the {@code queryMetadata} response header like the WAR's querySync, as application/json. WAR fidelity: PicsureRSv3 declares
     * class-level {@code @Produces("application/json")} and querySync has NO method-level override -- only {@code /query/{id}/result} is
     * octet-stream. Labeling sync octet-stream breaks the frontend, whose api layer intentionally treats octet-stream responses as binary
     * downloads (ArrayBuffer), swallowing count results.
     */
    static ResponseEntity<byte[]> syncResponse(QueryService.QuerySyncResponse r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (r.queryMetadata() != null) {
            b.header("queryMetadata", r.queryMetadata());
        }
        return b.body(r.body());
    }
}
