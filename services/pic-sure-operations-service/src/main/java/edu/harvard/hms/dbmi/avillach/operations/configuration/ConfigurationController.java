package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Exposes configuration reads and administrative writes. Mappings are slash-less because Spring 6 serves exactly the declared form and
 * PIC-SURE clients use slash-less URLs. The two plain {@code GET}s are public, permitted by
 * {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig}; each admin write requires the {@code SUPER_ADMIN} authority
 * through its own {@code @PreAuthorize}.
 *
 * <p>Admin writes return {@code 200 + entity body}, including DELETE returning the deleted configuration. This differs from surfaces that
 * return {@code 201} for creates and preserves the documented configuration API contract.
 */
@Tag(name = "Configuration", description = "Site configuration entries the UI reads and administrators manage")
@RestController
@RequestMapping("/configuration")
public class ConfigurationController {

    private final ConfigurationService service;

    public ConfigurationController(ConfigurationService service) {
        this.service = service;
    }

    @Operation(summary = "List configuration entries, optionally filtered by kind")
    @ApiResponse(responseCode = "200", description = "The matching configuration entries")
    @GetMapping("")
    public List<ConfigurationDto> getConfigurations(@RequestParam(name = "kind", required = false) String kind) {
        return service.getConfigurations(kind);
    }

    @Operation(summary = "Read one configuration entry by UUID or name")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The configuration entry"),
            @ApiResponse(responseCode = "404", description = "No configuration with that identifier")}
    )
    @GetMapping("/{identifier}")
    public ConfigurationDto getConfigurationById(@PathVariable("identifier") String identifier) {
        return service.getByIdentifier(identifier);
    }

    @Operation(summary = "Create a configuration entry")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The created configuration entry"),
            @ApiResponse(responseCode = "400", description = "Name, kind, or value missing"),
            @ApiResponse(responseCode = "409", description = "An entry with that name and kind exists")}
    )
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping("/admin")
    public ConfigurationDto addConfiguration(@Valid @RequestBody ConfigurationRequestDto request) {
        if (request.name() == null || request.kind() == null || request.value() == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Name, Kind, and Value properties must not be null");
        }
        return service.create(request);
    }

    @Operation(summary = "Update the given fields of a configuration entry")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The updated configuration entry"),
            @ApiResponse(responseCode = "400", description = "UUID in the body differs from the path"),
            @ApiResponse(responseCode = "404", description = "No configuration with that identifier"),
            @ApiResponse(responseCode = "409", description = "An entry with that name and kind exists")}
    )
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PatchMapping("/admin/{id}")
    public ConfigurationDto updateConfiguration(@PathVariable("id") UUID id, @Valid @RequestBody ConfigurationRequestDto request) {
        if (request.uuid() != null && !id.equals(request.uuid())) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "UUID cannot be changed");
        }
        return service.update(id, request);
    }

    @Operation(summary = "Delete a configuration entry and return it")
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The deleted configuration entry"),
            @ApiResponse(responseCode = "404", description = "No configuration with that identifier")}
    )
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping("/admin/{id}")
    public ConfigurationDto deleteConfiguration(@PathVariable("id") UUID id) {
        return service.delete(id);
    }
}
