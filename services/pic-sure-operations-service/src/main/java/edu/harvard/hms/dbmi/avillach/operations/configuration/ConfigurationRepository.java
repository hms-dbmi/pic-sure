package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


/** Spring Data repository for configurations, with derived queries used by {@link ConfigurationService}. */
public interface ConfigurationRepository extends JpaRepository<Configuration, UUID> {

    /** Finds configurations for the list endpoint's kind filter. */
    List<Configuration> findByKind(String kind);

    /** Finds configurations for name-based identifier lookup. */
    List<Configuration> findByName(String name);

    /**
     * Finds a configuration for the uniqueness check. The {@code unique_name_kind} constraint guarantees at most one match.
     */
    Optional<Configuration> findByNameAndKind(String name, String kind);
}
