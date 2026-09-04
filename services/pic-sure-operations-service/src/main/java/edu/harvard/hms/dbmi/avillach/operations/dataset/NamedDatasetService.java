package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Manages named datasets with user scoping enforced in SQL through {@code NamedDatasetRepository}'s
 * {@code findByUser}/{@code findByUuidAndUser}. The owner key is the caller's email, derived here from the {@link GatewayUser} via
 * {@link #requireEmail} (the identity-completeness check lives in this service, not the controller). Because the lookup is scoped at the
 * SQL layer, a caller reading/mutating another user's dataset gets exactly the same 404 as a genuinely-missing uuid -- there is no separate
 * 403 branch, and existence is never leaked to a non-owning caller (same posture as {@code ConfigurationService}'s not-found handling).
 *
 * <p>{@code archived} is not a list filter -- {@code findByUser} returns archived and non-archived rows alike; there is no soft-delete.
 *
 * <p>{@code queryId} is resolved to a persisted {@code Query} via {@code QueryRepository.findById}, returning 404 when absent.
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
    public List<NamedDatasetDto> listForUser(GatewayUser user) {
        return repo.findByUser(requireEmail(user)).stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public NamedDatasetDto getForUser(GatewayUser user, UUID id) {
        NamedDataset e = repo.findByUuidAndUser(id, requireEmail(user)).orElseThrow(() -> notFound(id));
        NamedDatasetDto dto = mapper.toDto(e);
        return dto;
    }

    @Transactional
    public NamedDatasetDto create(GatewayUser user, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        Query query = resolveQuery(req.queryId());
        NamedDataset saved = saveOrConflict(mapper.toEntity(email, query, req), req.queryId(), email);
        return mapper.toDto(saved);
    }

    @Transactional
    public NamedDatasetDto update(GatewayUser user, UUID id, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        NamedDataset existing = repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id));
        if (existing.getQuery() == null || !existing.getQuery().getUuid().equals(req.queryId())) {
            existing.setQuery(resolveQuery(req.queryId()));
        }
        existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
        return mapper.toDto(saveOrConflict(existing, req.queryId(), email));
    }

    @Transactional
    public void delete(GatewayUser user, UUID id) {
        NamedDataset existing = repo.findByUuidAndUser(id, requireEmail(user)).orElseThrow(() -> notFound(id));
        repo.delete(existing);
    }

    /**
     * Returns the caller's email owner key. {@code WebSecurityConfig} already rejects unauthenticated requests to {@code /dataset/**}
     * before any controller method runs, so this is a defensive guard against a gateway that authenticated the caller (sent
     * {@code X-User-Id}) but omitted {@code X-User-Email} -- not the primary auth gate.
     */
    private static String requireEmail(GatewayUser user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw PicsureExceptions.unauthorized("User identity (email) not present in request");
        }
        return user.getEmail();
    }

    private Query resolveQuery(UUID queryId) {
        return queryRepo.findById(queryId).orElseThrow(() -> PicsureExceptions.notFound("Query", queryId));
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
            throw PicsureExceptions.conflict("A NamedDataset for query " + queryId + " and user '" + user + "' already exists");
        }
    }

    private static PicsureException notFound(UUID id) {
        return PicsureExceptions.notFound("NamedDataset", id);
    }
}
