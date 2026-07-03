package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;

/**
 * Ports the legacy WildFly {@code NamedDatasetService}. User-scoping is pushed into SQL via {@code NamedDatasetRepository}'s
 * {@code findByUser}/{@code findByUuidAndUser}, replacing the WAR's in-Java owner check; the owner key is the caller's EMAIL. Because the
 * lookup is scoped at the SQL layer, a caller reading/mutating another user's dataset gets exactly the same 404 as a genuinely-missing uuid
 * -- there is no separate 403 branch, and existence is never leaked to a non-owning caller (same posture as {@code ConfigurationService}'s
 * not-found handling).
 *
 * <p>{@code archived} is not a list filter -- {@code findByUser} returns archived and non-archived rows alike; there is no soft-delete.
 *
 * <p>{@code queryId} is resolved to a persisted {@code Query} via {@code QueryRepository.findById}, 404 when absent (preserving the WAR's
 * "query not found" branch).
 */
@Service
public class NamedDatasetService {

    private final NamedDatasetRepository repo;
    private final QueryRepository queryRepo;
    private final NamedDatasetMapper mapper;

    public NamedDatasetService(NamedDatasetRepository repo, QueryRepository queryRepo, NamedDatasetMapper mapper) {
        this.repo = repo;
        this.queryRepo = queryRepo;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<NamedDatasetDto> listForUser(String user) {
        return repo.findByUser(user).stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public NamedDatasetDto getForUser(String user, UUID id) {
        NamedDataset e = repo.findByUuidAndUser(id, user).orElseThrow(() -> notFound(id));
        return mapper.toDto(e);
    }

    @Transactional
    public NamedDatasetDto create(String user, NamedDatasetRequestDto req) {
        Query query = resolveQuery(req.queryId());
        NamedDataset saved = repo.save(mapper.toEntity(user, query, req));
        return mapper.toDto(saved);
    }

    @Transactional
    public NamedDatasetDto update(String user, UUID id, NamedDatasetRequestDto req) {
        NamedDataset existing = repo.findByUuidAndUser(id, user).orElseThrow(() -> notFound(id));
        if (existing.getQuery() == null || !existing.getQuery().getUuid().equals(req.queryId())) {
            existing.setQuery(resolveQuery(req.queryId()));
        }
        existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
        return mapper.toDto(repo.save(existing));
    }

    @Transactional
    public void delete(String user, UUID id) {
        NamedDataset existing = repo.findByUuidAndUser(id, user).orElseThrow(() -> notFound(id));
        repo.delete(existing);
    }

    private Query resolveQuery(UUID queryId) {
        return queryRepo.findById(queryId)
            .orElseThrow(() -> new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Query " + queryId + " not found"));
    }

    private static PicsureException notFound(UUID id) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", "NamedDataset " + id + " not found");
    }
}
