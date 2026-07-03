package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;

/**
 * Note: the plan brief this task was ported from assumed a {@code PicsureNotFoundException} subclass. Neither exists in the actual
 * {@code pic-sure-spring-commons} built for this monorepo (it ships only the single public {@link PicsureException}, carrying status via
 * {@code getStatus()}) -- so every not-found case here asserts {@code PicsureException} with {@code HttpStatus.NOT_FOUND}, consistent with
 * how {@code ConfigurationService} already expresses its not-found cases.
 *
 * <p>Cross-user access (§ email-scoping): {@code findByUuidAndUser} scopes the lookup at the SQL layer, so a caller reading/mutating
 * another user's dataset gets exactly the same {@code PicsureException(NOT_FOUND)} as a genuinely-missing uuid -- there is no separate 403
 * branch, and existence is never leaked to a non-owning caller.
 */
class NamedDatasetServiceTest {

    NamedDatasetRepository repo = mock(NamedDatasetRepository.class);
    QueryRepository queryRepo = mock(QueryRepository.class);
    NamedDatasetMapper mapper = new NamedDatasetMapper();
    NamedDatasetService service = new NamedDatasetService(repo, queryRepo, mapper);

    @Test
    void listsByUserEmail() {
        Query q = new Query();
        q.setUuid(UUID.randomUUID());
        NamedDataset e = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(q);
        e.setUuid(UUID.randomUUID());
        when(repo.findByUser("alice@example.com")).thenReturn(List.of(e));

        List<NamedDatasetDto> result = service.listForUser("alice@example.com");

        assertThat(result).hasSize(1).extracting(NamedDatasetDto::name).containsExactly("d1");
    }

    @Test
    void findByIdScopedToUserThrows404WhenAbsentOrNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(PicsureException.class, () -> service.getForUser("alice@example.com", id));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getForUserReturnsDtoWhenOwned() {
        UUID id = UUID.randomUUID();
        NamedDataset e = new NamedDataset().setUser("alice@example.com").setName("d1");
        e.setUuid(id);
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.of(e));

        NamedDatasetDto dto = service.getForUser("alice@example.com", id);

        assertThat(dto.name()).isEqualTo("d1");
    }

    @Test
    void createResolvesQueryAndReturnsDto() {
        UUID queryId = UUID.randomUUID();
        Query q = new Query();
        q.setUuid(queryId);
        when(queryRepo.findById(queryId)).thenReturn(Optional.of(q));
        when(repo.save(any())).thenAnswer(inv -> {
            NamedDataset e = inv.getArgument(0);
            e.setUuid(UUID.randomUUID());
            return e;
        });

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "d2", false, null);
        NamedDatasetDto dto = service.create("alice@example.com", req);

        assertThat(dto.name()).isEqualTo("d2");
        assertThat(dto.queryId()).isEqualTo(queryId);
    }

    @Test
    void createWithMissingQueryThrows404() {
        UUID queryId = UUID.randomUUID();
        when(queryRepo.findById(queryId)).thenReturn(Optional.empty());
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "d3", false, null);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.create("alice@example.com", req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateScopedToUserThrows404WhenNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.empty());
        NamedDatasetRequestDto req = new NamedDatasetRequestDto(UUID.randomUUID(), "renamed", false, null);

        PicsureException ex = assertThrows(PicsureException.class, () -> service.update("alice@example.com", id, req));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAppliesNameArchivedAndMetadata() {
        UUID id = UUID.randomUUID();
        UUID queryId = UUID.randomUUID();
        Query q = new Query();
        q.setUuid(queryId);
        NamedDataset existing = new NamedDataset().setUser("alice@example.com").setName("old").setQuery(q).setArchived(false);
        existing.setUuid(id);
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(queryId, "renamed", true, null);
        NamedDatasetDto dto = service.update("alice@example.com", id, req);

        assertThat(dto.name()).isEqualTo("renamed");
        assertThat(dto.archived()).isTrue();
    }

    @Test
    void updateResolvesNewQueryWhenQueryIdChanges() {
        UUID id = UUID.randomUUID();
        UUID oldQueryId = UUID.randomUUID();
        UUID newQueryId = UUID.randomUUID();
        Query oldQuery = new Query();
        oldQuery.setUuid(oldQueryId);
        Query newQuery = new Query();
        newQuery.setUuid(newQueryId);
        NamedDataset existing = new NamedDataset().setUser("alice@example.com").setName("d1").setQuery(oldQuery);
        existing.setUuid(id);
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.of(existing));
        when(queryRepo.findById(newQueryId)).thenReturn(Optional.of(newQuery));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NamedDatasetRequestDto req = new NamedDatasetRequestDto(newQueryId, "d1", false, null);
        NamedDatasetDto dto = service.update("alice@example.com", id, req);

        assertThat(dto.queryId()).isEqualTo(newQueryId);
    }

    @Test
    void deleteScopedToUserThrows404WhenNotOwned() {
        UUID id = UUID.randomUUID();
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.empty());

        PicsureException ex = assertThrows(PicsureException.class, () -> service.delete("alice@example.com", id));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesOwnedDataset() {
        UUID id = UUID.randomUUID();
        NamedDataset existing = new NamedDataset().setUser("alice@example.com").setName("d1");
        existing.setUuid(id);
        when(repo.findByUuidAndUser(id, "alice@example.com")).thenReturn(Optional.of(existing));

        service.delete("alice@example.com", id);

        verify(repo).delete(existing);
    }
}
