package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import jakarta.validation.Valid;

/**
 * Ports the legacy WildFly {@code NamedDatasetRS}: {@code GET/POST /dataset/named} and {@code GET/PUT/DELETE /dataset/named/{id}}. Mappings
 * are slash-less on purpose: the frontend calls the slash-less form, and Spring 6 -- unlike the legacy JAX-RS runtime, which matched both
 * -- serves EXACTLY the declared form (a trailing-slash mapping 404s the slash-less request). Authorization is enforced in two layers:
 * {@code WebSecurityConfig} requires an authenticated caller for all of {@code /dataset/**} (the gateway must have supplied
 * {@code X-User-Id}); this controller then email-scopes every operation via {@link NamedDatasetService}, whose repository lookups are keyed
 * on the caller's email ({@code GatewayUser#getEmail()}), never {@code userId}.
 *
 * <p>DELETE is net-new relative to the legacy {@code NamedDatasetRS} (which had no delete endpoint) -- added here per the migration plan.
 */
@RestController
@RequestMapping("/dataset/named")
public class NamedDatasetController {

    private final NamedDatasetService service;

    public NamedDatasetController(NamedDatasetService service) {
        this.service = service;
    }

    @GetMapping("")
    public List<NamedDatasetDto> list(GatewayUser user) {
        return service.listForUser(requireEmail(user));
    }

    @PostMapping("")
    public ResponseEntity<NamedDatasetDto> create(GatewayUser user, @Valid @RequestBody NamedDatasetRequestDto req) {
        NamedDatasetDto created = service.create(requireEmail(user), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public NamedDatasetDto get(GatewayUser user, @PathVariable("id") UUID id) {
        return service.getForUser(requireEmail(user), id);
    }

    @PutMapping("/{id}")
    public NamedDatasetDto update(GatewayUser user, @PathVariable("id") UUID id, @Valid @RequestBody NamedDatasetRequestDto req) {
        return service.update(requireEmail(user), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(GatewayUser user, @PathVariable("id") UUID id) {
        service.delete(requireEmail(user), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the caller's email owner key. {@code WebSecurityConfig} already rejects unauthenticated requests to {@code /dataset/**}
     * before this method runs, so this is a defensive guard against a gateway that authenticated the caller (sent {@code X-User-Id}) but
     * omitted {@code X-User-Email} -- not the primary auth gate.
     */
    private String requireEmail(GatewayUser user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "User identity (email) not present in request");
        }
        return user.getEmail();
    }
}
