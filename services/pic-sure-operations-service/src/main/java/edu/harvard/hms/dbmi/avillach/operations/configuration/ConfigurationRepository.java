package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.ConfigurationRepository} (CDI/{@code BaseRepository}).
 * {@code findById}/{@code findAll}/{@code save}/{@code delete} are inherited from {@link JpaRepository}; the derived queries below replace
 * the legacy {@code BaseRepository.getByColumn}/{@code getByColumns} calls used by {@code ConfigurationService}.
 */
public interface ConfigurationRepository extends JpaRepository<Configuration, UUID> {

    /** Replaces {@code getByColumn("kind", kind)} -- the kind filter on the list endpoint. */
    List<Configuration> findByKind(String kind);

    /** Replaces {@code getByColumn("name", name)} -- the name-based identifier lookup. */
    List<Configuration> findByName(String name);

    /**
     * Replaces {@code getByColumns({name, kind})} for the uniqueness check. The unique constraint {@code unique_name_kind} guarantees at
     * most one match.
     */
    Optional<Configuration> findByNameAndKind(String name, String kind);
}
