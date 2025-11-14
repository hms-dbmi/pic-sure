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

    // If the request has no uuid it's new so match any uuid with this name & kind
    // else, match any uuid with this name and kind that isn't the one we're updating
    private boolean nameKindPairExists(ConfigurationRequest request) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("name", request.getName());
        columns.put("kind", request.getKind());
        return configurationRepository.getByColumns(configurationRepository.query(), columns).stream().map(Configuration::getUuid)
            .anyMatch(uuid -> request.getUuid() == null || !uuid.equals(request.getUuid()));
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
            if (nameKindPairExists(request)) {
                logger.error("Error persisting configuration: name already exists " + request.getName());
                return Optional.empty();
            }

            Configuration config = new Configuration().setName(request.getName()).setKind(request.getKind()).setValue(request.getValue())
                .setDescription(request.getDescription());
            configurationRepository.persist(config);
            logger.debug("Added configuration: " + config.getUuid());
            return Optional.of(config);
        } catch (Exception exception) {
            logger.error("Error persisting configuration " + request.getName(), exception.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public Optional<Configuration> updateConfiguration(UUID configurationId, ConfigurationRequest request) {
        try {
            request.setUuid(configurationId); // make sure request has uuid as it's optional
            if (nameKindPairExists(request)) {
                logger.error("Error updating configuration: new name already exists " + request.getName());
                return Optional.empty();
            }

            Configuration config = configurationRepository.getById(configurationId);
            if (config == null) {
                logger.error("Configuration not found with id " + configurationId.toString());
                return Optional.empty();
            }
            config.setName(request.getName()).setValue(request.getValue()).setKind(request.getKind())
                .setDescription(request.getDescription()).setDeleteRequested(request.getDeleteRequested());
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
