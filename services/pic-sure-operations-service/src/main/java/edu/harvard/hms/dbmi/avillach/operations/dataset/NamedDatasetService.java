package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

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
 *
 * <p>{@code NamedDataset} carries a DB-level unique constraint on {@code (queryId, user)}. Rather than a check-then-save pre-check (which
 * would race under concurrent requests), {@link #create} and {@link #update} (when it repoints the query) use {@code saveAndFlush} inside a
 * try/catch so the constraint violation surfaces synchronously and is translated to {@code 409 CONFLICT} via {@link PicsureException},
 * mirroring how {@code ConfigurationService} handles its name+kind conflict.
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
        NamedDataset saved = saveOrConflict(mapper.toEntity(user, query, req), req.queryId(), user);
        return mapper.toDto(saved);
    }

    @Transactional
    public NamedDatasetDto update(String user, UUID id, NamedDatasetRequestDto req) {
        NamedDataset existing = repo.findByUuidAndUser(id, user).orElseThrow(() -> notFound(id));
        if (existing.getQuery() == null || !existing.getQuery().getUuid().equals(req.queryId())) {
            existing.setQuery(resolveQuery(req.queryId()));
        }
        existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
        return mapper.toDto(saveOrConflict(existing, req.queryId(), user));
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

    /**
     * {@code saveAndFlush} (not {@code save}) so the {@code unique_queryId_user} constraint violation -- if any -- is raised by the
     * database and thrown here, inside the try/catch, rather than deferred to end-of-transaction flush where it could no longer be
     * translated into a 409.
     */
    private NamedDataset saveOrConflict(NamedDataset entity, UUID queryId, String user) {
        try {
            return repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw conflict(queryId, user);
        }
    }

    private static PicsureException notFound(UUID id) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", "NamedDataset " + id + " not found");
    }

    private static PicsureException conflict(UUID queryId, String user) {
        return new PicsureException(
            HttpStatus.CONFLICT, "conflict", "A NamedDataset for query " + queryId + " and user '" + user + "' already exists"
        );
    }
}
