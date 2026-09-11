package edu.harvard.hms.dbmi.avillach.operations.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/** Tests configuration behavior using the shared {@link PicsureException} status contract. */
class ConfigurationServiceTest {

    ConfigurationRepository repo = mock(ConfigurationRepository.class);
    ConfigurationMapper mapper = new ConfigurationMapper();
    ConfigurationService service = new ConfigurationService(repo, mapper);

    @Test
    void listAllWhenNoKind() {
        Configuration c = new Configuration().setName("A").setKind("ui");
        c.setUuid(UUID.randomUUID());
        when(repo.findAll()).thenReturn(List.of(c));
        assertThat(service.getConfigurations(null)).hasSize(1);
        verify(repo).findAll();
        verify(repo, never()).findByKind(any());
    }

    @Test
    void listByKindWhenKindGiven() {
        when(repo.findByKind("ui")).thenReturn(List.of());
        service.getConfigurations("ui");
        verify(repo).findByKind("ui");
    }

    @Test
    void getByIdentifierResolvesUuid() {
        UUID id = UUID.randomUUID();
        Configuration c = new Configuration().setName("A").setKind("ui");
        c.setUuid(id);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        assertThat(service.getByIdentifier(id.toString()).uuid()).isEqualTo(id);
    }

    @Test
    void getByIdentifierFallsBackToName() {
        when(repo.findByName("FEATURE_X")).thenReturn(List.of(new Configuration().setName("FEATURE_X").setKind("ui")));
        assertThat(service.getByIdentifier("FEATURE_X").name()).isEqualTo("FEATURE_X");
    }

    @Test
    void getByIdentifierNotFoundThrows404() {
        when(repo.findByName("nope")).thenReturn(List.of());
        PicsureException ex = assertThrows(PicsureException.class, () -> service.getByIdentifier("nope"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRejectsDuplicateNameKind() {
        when(repo.findByNameAndKind("A", "ui")).thenReturn(Optional.of(new Configuration().setName("A").setKind("ui")));
        ConfigurationRequestDto req = new ConfigurationRequestDto(null, "A", "ui", "true", null, null);
        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(req));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createPersistsAndReturnsDto() {
        when(repo.findByNameAndKind("A", "ui")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            Configuration c = inv.getArgument(0);
            c.setUuid(UUID.randomUUID());
            return c;
        });
        ConfigurationRequestDto req = new ConfigurationRequestDto(null, "A", "ui", "true", "desc", false);
        ConfigurationDto dto = service.create(req);
        assertThat(dto.name()).isEqualTo("A");
        assertThat(dto.value()).isEqualTo("true");
    }

    @Test
    void createRaceLostToConcurrentDuplicateThrows409() {
        when(repo.findByNameAndKind("A", "ui")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_name_kind"));

        ConfigurationRequestDto req = new ConfigurationRequestDto(null, "A", "ui", "true", null, null);
        PicsureException ex = assertThrows(PicsureException.class, () -> service.create(req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateMissingThrows404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(id, new ConfigurationRequestDto(null, "A", "ui", "v", null, null)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateRejectsClashWithAnotherRow() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Configuration existing = new Configuration().setName("A").setKind("ui");
        existing.setUuid(id);
        Configuration other = new Configuration().setName("B").setKind("ui");
        other.setUuid(otherId);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.findByNameAndKind("B", "ui")).thenReturn(Optional.of(other));

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(id, new ConfigurationRequestDto(null, "B", "ui", null, null, null)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateRaceLostToConcurrentDuplicateThrows409() {
        UUID id = UUID.randomUUID();
        Configuration existing = new Configuration().setName("A").setKind("ui");
        existing.setUuid(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.findByNameAndKind("B", "ui")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique_name_kind"));

        PicsureException ex =
            assertThrows(PicsureException.class, () -> service.update(id, new ConfigurationRequestDto(null, "B", "ui", null, null, null)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateAllowsKeepingItsOwnNameAndKind() {
        UUID id = UUID.randomUUID();
        Configuration existing = new Configuration().setName("A").setKind("ui").setValue("old");
        existing.setUuid(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.findByNameAndKind("A", "ui")).thenReturn(Optional.of(existing));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfigurationDto dto = service.update(id, new ConfigurationRequestDto(null, "A", "ui", "new", null, null));

        assertThat(dto.value()).isEqualTo("new");
    }

    @Test
    void deleteMissingThrows404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        PicsureException ex = assertThrows(PicsureException.class, () -> service.delete(id));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteReturnsDeletedConfiguration() {
        UUID id = UUID.randomUUID();
        Configuration existing = new Configuration().setName("A").setKind("ui");
        existing.setUuid(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        ConfigurationDto dto = service.delete(id);

        assertThat(dto.uuid()).isEqualTo(id);
        verify(repo).delete(existing);
    }
}
