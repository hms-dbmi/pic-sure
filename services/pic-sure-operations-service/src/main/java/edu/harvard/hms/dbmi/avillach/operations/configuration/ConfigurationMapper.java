package edu.harvard.hms.dbmi.avillach.operations.configuration;

import org.springframework.stereotype.Component;


/**
 * Translates between the {@code pic-sure-api-data} {@link Configuration} entity and this service's DTOs. Replicates the partial-update
 * semantics of the legacy entity's {@code fromRequest}/{@code patch} helpers, which were intentionally dropped from the ported entity (they
 * depended on the javax {@code ConfigurationRequest} DTO and the JSON-P API).
 */
@Component
public class ConfigurationMapper {

    public ConfigurationDto toDto(Configuration c) {
        return new ConfigurationDto(c.getUuid(), c.getName(), c.getKind(), c.getValue(), c.getDescription(), c.getMarkForDelete());
    }

    /** Build a fresh entity from a create request (mirrors the legacy {@code Configuration.fromRequest}). */
    public Configuration toEntity(ConfigurationRequestDto req) {
        return applyPatch(new Configuration(), req);
    }

    /** Apply only the non-null fields (mirrors the legacy {@code Configuration.patch} -- PATCH semantics). */
    public Configuration applyPatch(Configuration c, ConfigurationRequestDto req) {
        if (req.name() != null) {
            c.setName(req.name());
        }
        if (req.kind() != null) {
            c.setKind(req.kind());
        }
        if (req.value() != null) {
            c.setValue(req.value());
        }
        if (req.description() != null) {
            c.setDescription(req.description());
        }
        if (req.markForDelete() != null) {
            c.setMarkForDelete(req.markForDelete());
        }
        return c;
    }
}
