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

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Exposes {@code GET/POST /dataset/named} and {@code GET/PUT/DELETE /dataset/named/{id}}. Mappings are slash-less because the frontend uses
 * that form and Spring 6 serves exactly the declared mapping. Authorization is enforced in two layers: {@code WebSecurityConfig} requires
 * an authenticated caller for all of {@code /dataset/**} (the gateway must have supplied {@code X-User-Id}); {@link NamedDatasetService}
 * then email-scopes every operation, requiring and keying on the caller's email ({@code GatewayUser#getEmail()}), never {@code userId}.
 * This controller is a thin HTTP adapter -- identity validation lives in the service.
 *
 * <p>The one check made on the caller's user id is in {@code PUT /dataset/named/{id}}: a query can be linked to a dataset only by the user
 * it belongs to, and query rows record their owner by user id.
 */
@Tag(name = "Named datasets", description = "Saved queries a user names and reuses, scoped to the caller")
@RestController
@RequestMapping("/dataset/named")
public class NamedDatasetController {

    static final String UPDATE_DESCRIPTION = "Replaces the name, archived state and metadata of the dataset.\n\n"
        + "When queryId names a query the dataset does not link yet, that query is linked as well and appears in queries. The query "
        + "must have been submitted by the caller, or be a query stored without an owner. A dataset links at most one query for each "
        + "pair of sample and result type, so a second query of a pair it already holds is refused. Linking does not change the query "
        + "member of the dataset, which stays the query the dataset was created from.\n\n"
        + "When queryId names a query the dataset already links, nothing is linked.";

    private final NamedDatasetService service;

    public NamedDatasetController(NamedDatasetService service) {
        this.service = service;
    }

    @Operation(summary = "List the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The caller's named datasets"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request")}
    )
    @AuditEvent(type = "ACCESS", action = "named_dataset.list")
    @GetMapping("")
    public List<NamedDatasetDto> list(GatewayUser user) {
        return service.listForUser(user);
    }

    @Operation(summary = "Name a query as a dataset for the caller")
    @ApiResponses(
        {@ApiResponse(responseCode = "201", description = "The created named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "Query not found"),
            @ApiResponse(responseCode = "409", description = "The caller already named that query")}
    )
    @AuditEvent(type = "ACCESS", action = "named_dataset.modify")
    @PostMapping("")
    public ResponseEntity<NamedDatasetDto> create(GatewayUser user, @Valid @RequestBody NamedDatasetRequestDto req) {
        NamedDatasetDto created = service.create(user, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Read one of the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "No named dataset with that id for the caller")}
    )
    @AuditEvent(type = "ACCESS", action = "named_dataset.read")
    @GetMapping("/{id}")
    public NamedDatasetDto get(GatewayUser user, @PathVariable("id") UUID id) {
        return service.getForUser(user, id);
    }

    @Operation(summary = "Update one of the caller's named datasets, or link another query to it", description = UPDATE_DESCRIPTION)
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The updated named dataset"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "403", description = "The query to link belongs to another user"),
            @ApiResponse(
                responseCode = "404", description = "No named dataset with that id for the caller, or the new queryId matches no query"
            ),
            @ApiResponse(
                responseCode = "409",
                description = "The dataset already links a query with the same sample flag and result type as the query to link"
            )}
    )
    @AuditEvent(type = "ACCESS", action = "named_dataset.modify")
    @PutMapping("/{id}")
    public NamedDatasetDto update(GatewayUser user, @PathVariable("id") UUID id, @Valid @RequestBody NamedDatasetRequestDto req) {
        return service.update(user, id, req);
    }

    @Operation(summary = "Delete one of the caller's named datasets")
    @ApiResponses(
        {@ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "401", description = "No caller email in the request"),
            @ApiResponse(responseCode = "404", description = "No named dataset with that id for the caller")}
    )
    @AuditEvent(type = "ACCESS", action = "named_dataset.delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(GatewayUser user, @PathVariable("id") UUID id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
