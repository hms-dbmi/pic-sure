package edu.harvard.hms.dbmi.avillach.operations.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.data.entity.Configuration;

class ConfigurationMapperTest {

    private final ConfigurationMapper mapper = new ConfigurationMapper();

    @Test
    void toDtoCopiesAllFields() {
        UUID id = UUID.randomUUID();
        Configuration config =
            new Configuration().setName("A").setKind("ui").setValue("true").setDescription("desc").setMarkForDelete(true);
        config.setUuid(id);

        ConfigurationDto dto = mapper.toDto(config);

        assertThat(dto.uuid()).isEqualTo(id);
        assertThat(dto.name()).isEqualTo("A");
        assertThat(dto.kind()).isEqualTo("ui");
        assertThat(dto.value()).isEqualTo("true");
        assertThat(dto.description()).isEqualTo("desc");
        assertThat(dto.markForDelete()).isTrue();
    }

    @Test
    void toEntityBuildsFreshEntityFromRequest() {
        ConfigurationRequestDto req = new ConfigurationRequestDto(null, "A", "ui", "true", "desc", false);

        Configuration config = mapper.toEntity(req);

        assertThat(config.getName()).isEqualTo("A");
        assertThat(config.getKind()).isEqualTo("ui");
        assertThat(config.getValue()).isEqualTo("true");
        assertThat(config.getDescription()).isEqualTo("desc");
        assertThat(config.getMarkForDelete()).isFalse();
    }

    @Test
    void applyPatchOnlyChangesNonNullFields() {
        Configuration existing =
            new Configuration().setName("A").setKind("ui").setValue("old").setDescription("old-desc").setMarkForDelete(false);

        ConfigurationRequestDto patch = new ConfigurationRequestDto(null, null, null, "new-value", null, null);
        mapper.applyPatch(existing, patch);

        assertThat(existing.getName()).isEqualTo("A");
        assertThat(existing.getKind()).isEqualTo("ui");
        assertThat(existing.getValue()).isEqualTo("new-value");
        assertThat(existing.getDescription()).isEqualTo("old-desc");
        assertThat(existing.getMarkForDelete()).isFalse();
    }
}
