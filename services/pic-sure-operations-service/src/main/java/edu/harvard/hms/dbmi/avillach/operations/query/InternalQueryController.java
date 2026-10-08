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

import edu.harvard.dbmi.avillach.domain.DispatchResponse;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SavedQueryReference;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * The internal query API: the token-gated boundary the hpds-query-service calls to save, read and update queries. {@code /internal/**}
 * passes {@code WebSecurityConfig}'s {@code anyRequest().permitAll()} unauthenticated -- it is {@link InternalTokenFilter} (a plain servlet
 * filter, not this controller) that actually gates every request here on {@code X-PIC-SURE-INTERNAL-TOKEN}, and network isolation (out of
 * this service's hands) that keeps it unreachable from outside the cluster.
 *
 * <p>{@code GET /{picsureId}/dispatch} answers {@code {"queryJson": "<string>"}}, 404 for an unknown id and 403 for a bad or missing token.
 * No service in this repository calls it. The gateway's {@code QueryAuthFetcher} was written against it and has since been removed.
 *
 * <p>{@code @Hidden} because no developer holding a user token can ever reach this controller: it is called machine-to-machine by
 * hpds-query-service, gated by a shared secret, not by the caller's identity.
 */
@Hidden
@RestController
@RequestMapping("/internal/queries")
public class InternalQueryController {

    private final QueryPersistenceService service;

    public InternalQueryController(QueryPersistenceService service) {
        this.service = service;
    }

    /**
     * Persists a query and answers 201 with the id it was stored under, as {@code {"picsureId": "<uuid>"}}.
     *
     * @param req the query to persist
     * @return the id of the persisted query
     */
    @AuditEvent(type = "OTHER", action = "internal_query.save")
    @PostMapping("")
    public ResponseEntity<SavedQueryReference> save(@RequestBody SaveQueryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new SavedQueryReference(service.save(req)));
    }

    @AuditEvent(type = "OTHER", action = "internal_query.read")
    @GetMapping("/{picsureId}")
    public StoredQuery get(@PathVariable("picsureId") UUID picsureId) {
        return service.get(picsureId);
    }

    @AuditEvent(type = "OTHER", action = "internal_query.update")
    @PatchMapping("/{picsureId}")
    public ResponseEntity<Void> update(@PathVariable("picsureId") UUID picsureId, @RequestBody UpdateQueryRequest req) {
        service.update(picsureId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Answers {@code {"queryJson": "<string>"}}: the stored query JSON re-serialized as a string with any legacy
     * {@code resourceCredentials} stripped, or {@code null} for a blank stored query.
     *
     * @param picsureId the id of the stored query
     * @return the stored query body
     */
    @AuditEvent(type = "OTHER", action = "internal_query.dispatch")
    @GetMapping("/{picsureId}/dispatch")
    public DispatchResponse dispatch(@PathVariable("picsureId") UUID picsureId) {
        return new DispatchResponse(service.dispatchQueryJson(picsureId));
    }
}
