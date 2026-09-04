package edu.harvard.hms.dbmi.avillach.operations.configuration;

import org.springframework.stereotype.Component;


/** Translates between the {@link Configuration} entity and DTOs, including partial-update semantics. */
@Component
public class ConfigurationMapper {

    public ConfigurationDto toDto(Configuration c) {
        return new ConfigurationDto(c.getUuid(), c.getName(), c.getKind(), c.getValue(), c.getDescription(), c.getMarkForDelete());
    }

    /** Builds a fresh entity from a create request. */
    public Configuration toEntity(ConfigurationRequestDto req) {
        return applyPatch(new Configuration(), req);
    }

    /** Applies only non-null fields for PATCH semantics. */
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
