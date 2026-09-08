package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


/**
 * Provides generic CRUD plus owner-scoped derived queries on the existing {@code uuid} and {@code user} columns. Applying user scoping in
 * SQL prevents callers from loading another user's dataset before ownership is checked.
 */
public interface NamedDatasetRepository extends JpaRepository<NamedDataset, UUID> {

    List<NamedDataset> findByUser(String user);

    Optional<NamedDataset> findByUuidAndUser(UUID uuid, String user);
}
