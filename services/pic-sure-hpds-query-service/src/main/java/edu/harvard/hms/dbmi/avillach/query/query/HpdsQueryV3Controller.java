package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * The sole HPDS query lifecycle ingress: {@code /hpds/{backend}/v3/query/**}. {@code {backend}} is {@code auth} or {@code open} (validated
 * downstream by {@link QueryService} via {@code HpdsBackendSelector}). The legacy v1 ingress ({@code /hpds/{backend}/query/**}) was
 * removed; new queries are always stored as version {@code "3"}. Read ops still dispatch HPDS-side on the STORED query's version, so
 * previously stored v1 rows remain retrievable via their v1 routes.
 *
 * <p><b>The contract is bare and typed.</b> Submissions bind the v3 {@link Query} record itself -- exactly the shape the gateway forwards
 * after consent mutation -- with no {@code QueryRequest} envelope: no {@code resourceUUID}, no {@code resourceCredentials}, no
 * {@code @type} discriminator. Strict deserialization (pic-sure-spring-commons) turns a leftover envelope field into a 400 instead of a
 * silently-dropped value. Reads carry no body at all: the stored query is their only input, and the response's identity is
 * {@code picsureId} -- the {@code resourceID} echo is gone with the envelope that carried it.
 *
 * <p>{@code ?isInstitute} and its 410 guard are also gone: federated queries were removed and, with the envelope, so was the
 * {@code FederatedQueryRequest} subtype that Jackson could silently reinterpret. There is no longer anything to guard against.
 */
@RestController
@RequestMapping("/hpds/{backend}/v3")
public class HpdsQueryV3Controller {

    private final QueryService service;

    public HpdsQueryV3Controller(QueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public QueryStatusResponse query(@PathVariable("backend") String backend, @RequestBody Query query) {
        return service.queryV3(backend, query);
    }

    @PostMapping("/query/sync")
    public ResponseEntity<byte[]> querySync(
        @PathVariable("backend") String backend, @RequestBody Query query,
        @RequestHeader(name = "request-source", required = false) String requestSource
    ) {
        return syncResponse(service.querySync(backend, query, requestSource));
    }

    @GetMapping("/query/{id}/status")
    public QueryStatusResponse status(@PathVariable("backend") String backend, @PathVariable("id") UUID id) {
        return service.status(backend, id);
    }

    @PostMapping(value = "/query/{id}/result", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> result(@PathVariable("backend") String backend, @PathVariable("id") UUID id) {
        return service.result(backend, id);
    }

    @PostMapping(value = "/query/{id}/signed-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public SignedUrlResponse signedUrl(@PathVariable("backend") String backend, @PathVariable("id") UUID id) {
        return service.signedUrl(backend, id);
    }

    @GetMapping("/query/{id}/metadata")
    public QueryStatusResponse metadata(@PathVariable("backend") String backend, @PathVariable("id") UUID id) {
        return service.metadata(id); // DB-only, backend irrelevant
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
