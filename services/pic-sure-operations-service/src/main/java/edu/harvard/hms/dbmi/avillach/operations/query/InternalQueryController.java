package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal query API: the token-gated boundary the hpds-query-service and the gateway both call. {@code /internal/**} passes
 * {@code WebSecurityConfig}'s {@code anyRequest().permitAll()} unauthenticated -- it is {@link InternalTokenFilter} (a plain servlet
 * filter, not this controller) that actually gates every request here on {@code X-PIC-SURE-INTERNAL-TOKEN}, and network isolation (out of
 * this service's hands) that keeps it unreachable from outside the cluster.
 *
 * <p>{@code GET /{picsureId}/dispatch} is the one FIXED external contract: the gateway's dormant {@code QueryAuthFetcher} already calls
 * {@code GET {base}/internal/queries/{id}/dispatch} expecting exactly {@code {"queryJson": "<string>"}} (deserialized as
 * {@code record DispatchResponse(String queryJson)}), 404 for an unknown id, 403 for a bad/missing token -- the key name, and that its
 * value is a JSON string (not a nested object), are load-bearing.
 */
@RestController
@RequestMapping("/internal/queries")
public class InternalQueryController {

    private final QueryPersistenceService service;

    public InternalQueryController(QueryPersistenceService service) {
        this.service = service;
    }

    @PostMapping("")
    public ResponseEntity<Map<String, UUID>> save(@RequestBody SaveQueryRequest req) {
        UUID picsureId = service.save(req);
        Map<String, UUID> body = new LinkedHashMap<>();
        body.put("picsureId", picsureId);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{picsureId}")
    public StoredQuery get(@PathVariable("picsureId") UUID picsureId) {
        return service.get(picsureId);
    }

    @PatchMapping("/{picsureId}")
    public ResponseEntity<Void> update(@PathVariable("picsureId") UUID picsureId, @RequestBody UpdateQueryRequest req) {
        service.update(picsureId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * MUST match the gateway's {@code QueryAuthFetcher} contract exactly: {@code {"queryJson": "<string>"}}, the stored query JSON
     * re-serialized as a string with {@code resourceCredentials} stripped (or {@code null} for a blank stored query).
     */
    @GetMapping("/{picsureId}/dispatch")
    public Map<String, String> dispatch(@PathVariable("picsureId") UUID picsureId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("queryJson", service.dispatchQueryJson(picsureId));
        return body;
    }
}
