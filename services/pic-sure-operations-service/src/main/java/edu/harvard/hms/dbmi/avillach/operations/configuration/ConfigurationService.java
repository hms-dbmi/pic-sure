package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;

/**
 * Provides configuration lookup and mutation. UUID-or-name lookup ({@link #getByIdentifier(String)}) tries {@link UUID#fromString}, looks
 * up a valid UUID by id, then falls back to the first {@code findByName} match. Create and update enforce name+kind uniqueness through
 * {@code findByNameAndKind}, excluding the row being updated. Updates apply partial changes through {@link ConfigurationMapper#applyPatch}.
 *
 * <p>Behavior upgrades to honest statuses: not-found -> 404, duplicate name+kind -> 409. Both are expressed as {@link PicsureException} --
 * the actual {@code pic-sure-spring-commons} built for this monorepo ships only that one public error class (no
 * {@code PicsureNotFoundException} subclass), so 404 is carried the same way as 409: via the status code on {@link PicsureException}, not
 * the exception's Java type.
 *
 * <p>{@code Configuration} carries a DB-level unique constraint on {@code (name, kind)}. The {@code findByNameAndKind} pre-check above
 * handles the common case, but a check-then-save is inherently racy under concurrent writers. {@link #create} and {@link #update} therefore
 * also use {@code saveAndFlush} inside a try/catch so a constraint violation that slips past the pre-check is translated to
 * {@code 409 CONFLICT} via {@link PicsureException}, mirroring {@code NamedDatasetService.saveOrConflict}.
 */
@Service
public class ConfigurationService {

    private final ConfigurationRepository repo;
    private final ConfigurationMapper mapper;

    public ConfigurationService(ConfigurationRepository repo, ConfigurationMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ConfigurationDto> getConfigurations(String kind) {
        List<Configuration> configs = (kind != null) ? repo.findByKind(kind) : repo.findAll();
        return configs.stream().map(mapper::toDto).toList();
    }

    /** Looks up a configuration by UUID, then by name. */
    @Transactional(readOnly = true)
    public ConfigurationDto getByIdentifier(String identifier) {
        return findEntityByIdentifier(identifier).map(mapper::toDto).orElseThrow(() -> notFound(identifier));
    }

    @Transactional
    public ConfigurationDto create(ConfigurationRequestDto req) {
        Configuration config = mapper.toEntity(req);
        if (repo.findByNameAndKind(config.getName(), config.getKind()).isPresent()) {
            throw conflict(config.getName(), config.getKind());
        }
        return mapper.toDto(saveOrConflict(config, config.getName(), config.getKind()));
    }

    @Transactional
    public ConfigurationDto update(UUID id, ConfigurationRequestDto req) {
        Configuration existing = repo.findById(id).orElseThrow(() -> notFound(id.toString()));
        String proposedName = req.name() != null ? req.name() : existing.getName();
        String proposedKind = req.kind() != null ? req.kind() : existing.getKind();
        boolean clash = repo.findByNameAndKind(proposedName, proposedKind).filter(other -> !other.getUuid().equals(id)).isPresent();
        if (clash) {
            throw conflict(proposedName, proposedKind);
        }
        mapper.applyPatch(existing, req);
        return mapper.toDto(saveOrConflict(existing, proposedName, proposedKind));
    }

    @Transactional
    public ConfigurationDto delete(UUID id) {
        Configuration existing = repo.findById(id).orElseThrow(() -> notFound(id.toString()));
        repo.delete(existing);
        return mapper.toDto(existing);
    }

    /**
     * {@code saveAndFlush} (not {@code save}) so the {@code unique_name_kind} constraint violation -- if any -- is raised by the database
     * and thrown here, inside the try/catch, rather than deferred to end-of-transaction flush where it could no longer be translated into a
     * 409. On {@link #update}, a violation here means the proposed {@code (name,kind)} collides with a different row that was created
     * concurrently after the pre-check ran -- same 409 as the create-path race.
     */
    private Configuration saveOrConflict(Configuration entity, String name, String kind) {
        try {
            return repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw conflict(name, kind);
        }
    }

    private Optional<Configuration> findEntityByIdentifier(String identifier) {
        try {
            UUID uuid = UUID.fromString(identifier);
            Optional<Configuration> byId = repo.findById(uuid);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException notAUuid) {
            // fall through to name lookup
        }
        return repo.findByName(identifier).stream().findFirst();
    }

    /** Delegates to the shared {@link PicsureExceptions} factories; only the entity-specific message text lives here. */
    private static PicsureException notFound(String identifier) {
        return PicsureExceptions.notFound("Configuration", identifier);
    }

    private static PicsureException conflict(String name, String kind) {
        return PicsureExceptions.conflict("A configuration with name '" + name + "' and kind '" + kind + "' already exists");
    }
}
