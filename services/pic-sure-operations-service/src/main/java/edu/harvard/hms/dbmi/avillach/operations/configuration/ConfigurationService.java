package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.data.entity.Configuration;
import edu.harvard.hms.dbmi.avillach.data.repository.ConfigurationRepository;

/**
 * Ports the legacy WildFly {@code ConfigurationService}. Preserved behaviors: (1) UUID-or-name lookup ({@link #getByIdentifier(String)}) --
 * try {@link UUID#fromString}; if valid, look up by id; if absent (or if the identifier is not a valid UUID at all), fall back to the first
 * {@code findByName} match. (2) name+kind uniqueness on create and update via {@code findByNameAndKind}, excluding the row being updated.
 * (3) partial PATCH via {@link ConfigurationMapper#applyPatch}.
 *
 * <p>Behavior upgrades to honest statuses (decision 10): not-found -> 404, duplicate name+kind -> 409. Both are expressed as
 * {@link PicsureException} -- the actual {@code pic-sure-spring-commons} built for this monorepo ships only that one public error class (no
 * {@code PicsureNotFoundException} subclass), so 404 is carried the same way as 409: via the status code on {@link PicsureException}, not
 * the exception's Java type.
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

    /** UUID-or-name lookup, preserving the legacy {@code ConfigurationService.getConfigurationByIdentifier}. */
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
        return mapper.toDto(repo.save(config));
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
        return mapper.toDto(repo.save(existing));
    }

    @Transactional
    public ConfigurationDto delete(UUID id) {
        Configuration existing = repo.findById(id).orElseThrow(() -> notFound(id.toString()));
        repo.delete(existing);
        return mapper.toDto(existing);
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

    private static PicsureException notFound(String identifier) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Configuration " + identifier + " not found");
    }

    private static PicsureException conflict(String name, String kind) {
        return new PicsureException(
            HttpStatus.CONFLICT, "conflict", "A configuration with name '" + name + "' and kind '" + kind + "' already exists"
        );
    }
}
