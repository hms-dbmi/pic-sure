package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Configuration;
import edu.harvard.dbmi.avillach.data.repository.ConfigurationRepository;
import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ConfigurationService {
    private final Logger logger = LoggerFactory.getLogger(ConfigurationRepository.class);

    @Inject
    ConfigurationRepository configurationRepository;

    // If the request has no uuid it's new so match any uuid with this name &/or kind
    // else, match any uuid with this name &/or kind that isn't the one we're updating
    private boolean nameKindPairExists(Configuration config) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("name", config.getName());
        columns.put("kind", config.getKind());
        return configurationRepository.getByColumns(configurationRepository.query(), columns).stream().map(Configuration::getUuid)
            .anyMatch(uuid -> config.getUuid() == null || !uuid.equals(config.getUuid()));
    }

    public Optional<List<Configuration>> getConfigurations(String kind) {
        try {
            List<Configuration> configs = kind != null ? configurationRepository.getByColumn("kind", kind) : configurationRepository.list();
            return Optional.ofNullable(configs);
        } catch (Exception exception) {
            logger.error("Error retrieving configurations " + (kind != null ? "with kind " + kind : ""), exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Configuration> getConfigurationByIdentifier(String identifier) {
        try {
            // Try to parse as UUID first
            UUID uuid = UUID.fromString(identifier);
            Configuration config = configurationRepository.getById(uuid);
            return Optional.ofNullable(config);
        } catch (IllegalArgumentException e) {
            // Not a valid UUID, treat as name
            List<Configuration> configs = configurationRepository.getByColumn("name", identifier);
            return configs != null && !configs.isEmpty() ? Optional.of(configs.get(0)) : Optional.empty();
        } catch (Exception exception) {
            logger.error("Error retrieving configurations with uuid " + identifier, exception.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public Optional<Configuration> addConfiguration(ConfigurationRequest request) {
        try {
            Configuration config = Configuration.fromRequest(request);

            if (nameKindPairExists(config)) {
                logger.error("Error persisting configuration: name already exists " + request.getName());
                return Optional.empty();
            }

            configurationRepository.persist(config);
            logger.debug("Added configuration: " + config.getUuid());
            return Optional.of(config);
        } catch (Exception exception) {
            logger.error("Error persisting configuration " + request.getName(), exception.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public Optional<Configuration> updateConfiguration(ConfigurationRequest request) {
        try {
            Configuration config = configurationRepository.getById(request.getUuid());
            if (config == null) {
                logger.error("Configuration not found with id " + request.getUuid().toString());
                return Optional.empty();
            }
            config.patch(request);

            if (nameKindPairExists(config)) {
                logger.error("Error updating configuration: new name already exists " + request.getName());
                return Optional.empty();
            }

            configurationRepository.merge(config);
            logger.debug("Updated configuration " + config.getUuid().toString() + "(" + config.getName() + ")");
            return Optional.of(config);
        } catch (Exception exception) {
            logger.error("Error updating configuration " + request.getName() + " with value " + request.getValue(), exception.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public Optional<Configuration> deleteConfiguration(UUID configurationId) {
        try {
            Configuration config = configurationRepository.getById(configurationId);
            if (config == null) {
                logger.error("Configuration not found with id " + configurationId.toString());
                return Optional.empty();
            }
            configurationRepository.remove(config);
            logger.debug("Deleted configuration " + config.getUuid().toString() + "(" + config.getName() + ")");
            return Optional.of(config);
        } catch (Exception exception) {
            logger.error("Error deleting configuration " + configurationId.toString(), exception.getMessage());
            return Optional.empty();
        }
    }
}
