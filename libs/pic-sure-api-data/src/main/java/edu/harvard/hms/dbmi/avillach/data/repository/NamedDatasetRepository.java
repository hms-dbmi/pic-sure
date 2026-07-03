package edu.harvard.hms.dbmi.avillach.data.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.repository.NamedDatasetRepository} (CDI/{@code BaseRepository}), which had
 * no custom finder methods beyond generic CRUD.
 *
 * <p>{@code findByUser}/{@code findByUuidAndUser} are added proactively (not present in legacy) because the downstream Phase-4
 * platform-service plan (docs/superpowers/plans/2026-05-09-phase-4-platform-service.md:44,651,1915) documents them as expected from this
 * foundational module, replacing the WAR's in-Java owner check with SQL-level user scoping. They are simple derived queries on existing
 * columns ({@code uuid}, {@code user}) and carry no risk to the entities/mappings this unit is responsible for.
 */
public interface NamedDatasetRepository extends JpaRepository<NamedDataset, UUID> {

    List<NamedDataset> findByUser(String user);

    Optional<NamedDataset> findByUuidAndUser(UUID uuid, String user);
}
