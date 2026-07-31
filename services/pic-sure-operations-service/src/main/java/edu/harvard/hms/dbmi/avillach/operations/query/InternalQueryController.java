package edu.harvard.hms.dbmi.avillach.operations.query;

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

import edu.harvard.dbmi.avillach.contracts.internal.DispatchResponse;
import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryResponse;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;

/**
 * The internal query API: the token-gated boundary the hpds-query-service and the gateway both call. {@code /internal/**} passes
 * {@code WebSecurityConfig}'s {@code anyRequest().permitAll()} unauthenticated -- it is {@link InternalTokenFilter} (a plain servlet
 * filter, not this controller) that actually gates every request here on {@code X-PIC-SURE-INTERNAL-TOKEN}, and network isolation (out of
 * this service's hands) that keeps it unreachable from outside the cluster.
 *
 * <p>Every body on this surface is a shared {@code edu.harvard.dbmi.avillach.contracts.internal} record -- the SAME classes the callers
 * bind ({@code OperationsClient} in hpds-query-service, {@code QueryAuthFetcher} in the gateway) -- so the two sides of this internal API
 * cannot drift apart silently. The wire bytes are unchanged from the hand-rolled maps they replaced: {@code status} is still the
 * {@link edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus} enum NAME and {@code metadata} still base64-encoded bytes.
 *
 * <p>{@code GET /{picsureId}/dispatch} is the one FIXED external contract: the gateway's {@code QueryAuthFetcher} calls {@code GET
 * {base}/internal/queries/{id}/dispatch} expecting exactly {@code {"queryJson": "<string>"}} ({@link DispatchResponse}), 404 for an unknown
 * id, 403 for a bad/missing token -- the key name, and that its value is a JSON string (not a nested object), are load-bearing.
 */
@RestController
@RequestMapping("/internal/queries")
public class InternalQueryController {

    private final QueryPersistenceService service;

    public InternalQueryController(QueryPersistenceService service) {
        this.service = service;
    }

    @PostMapping("")
    public ResponseEntity<SaveQueryResponse> save(@RequestBody SaveQueryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new SaveQueryResponse(service.save(req)));
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
     * MUST match the gateway's {@code QueryAuthFetcher} contract exactly: {@code {"queryJson": "<string>"}} carrying the BARE query JSON --
     * unwrapped from the legacy envelope when the row predates Task 15, {@code resourceCredentials} stripped either way, {@code null} for a
     * blank stored query. See {@link QueryPersistenceService#dispatchQueryJson(UUID)} for why that normalization happens here rather than
     * being left to the gateway's authorization rules.
     */
    @GetMapping("/{picsureId}/dispatch")
    public DispatchResponse dispatch(@PathVariable("picsureId") UUID picsureId) {
        return new DispatchResponse(service.dispatchQueryJson(picsureId));
    }
}
