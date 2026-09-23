package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import jakarta.validation.Valid;

/**
 * Exposes configuration reads and administrative writes. Mappings are slash-less because Spring 6 serves exactly the declared form and
 * PIC-SURE clients use slash-less URLs. Authorization is enforced entirely by
 * {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig} ({@code /configuration/admin/**} requires {@code SUPER_ADMIN};
 * the two plain {@code GET}s below are public) -- this controller carries no auth code of its own, and its mappings must match those
 * security-layer paths exactly.
 *
 * <p>Admin writes return {@code 200 + entity body}, including DELETE returning the deleted configuration. This differs from surfaces that
 * return {@code 201} for creates and preserves the documented configuration API contract.
 */
@RestController
@RequestMapping("/configuration")
public class ConfigurationController {

    private final ConfigurationService service;

    public ConfigurationController(ConfigurationService service) {
        this.service = service;
    }

    @GetMapping("")
    public List<ConfigurationDto> getConfigurations(@RequestParam(name = "kind", required = false) String kind) {
        return service.getConfigurations(kind);
    }

    @GetMapping("/{identifier}")
    public ConfigurationDto getConfigurationById(@PathVariable("identifier") String identifier) {
        return service.getByIdentifier(identifier);
    }

    @PostMapping("/admin")
    public ConfigurationDto addConfiguration(@Valid @RequestBody ConfigurationRequestDto request) {
        if (request.name() == null || request.kind() == null || request.value() == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Name, Kind, and Value properties must not be null");
        }
        return service.create(request);
    }

    @PatchMapping("/admin/{id}")
    public ConfigurationDto updateConfiguration(@PathVariable("id") UUID id, @Valid @RequestBody ConfigurationRequestDto request) {
        if (request.uuid() != null && !id.equals(request.uuid())) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "UUID cannot be changed");
        }
        return service.update(id, request);
    }

    @DeleteMapping("/admin/{id}")
    public ConfigurationDto deleteConfiguration(@PathVariable("id") UUID id) {
        return service.delete(id);
    }
}
