package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;


/**
 * Provides generic CRUD plus owner-scoped derived queries on the existing {@code uuid} and {@code user} columns. Applying user scoping in
 * SQL prevents callers from loading another user's dataset before ownership is checked.
 */
public interface NamedDatasetRepository extends JpaRepository<NamedDataset, UUID> {

    List<NamedDataset> findByUser(String user);

    Optional<NamedDataset> findByUuidAndUser(UUID uuid, String user);

    /**
     * Finds one of a user's datasets and locks its row for the rest of the caller's transaction, as {@code SELECT ... FOR UPDATE} does. A
     * second transaction that asks for the same dataset waits here until the first one ends, and then reads the dataset as the first one
     * left it. Only the dataset row is locked, not the query row it points at. Call it inside a transaction, before reading anything else
     * that belongs to the dataset.
     *
     * @param uuid the id of the dataset
     * @param user the email the dataset is stored under
     * @return the locked dataset, or empty when the user has no dataset with the id
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NamedDataset> findLockedByUuidAndUser(UUID uuid, String user);

    /**
     * Tells whether a user already has a dataset that points at a query row. The table allows one dataset for each pair of query row and
     * user, so a second dataset of that user cannot be pointed at the row.
     *
     * @param queryUuid the id of the query row
     * @param user the email the datasets are stored under
     * @return true when a dataset of the user points at the query row
     */
    boolean existsByQueryUuidAndUser(UUID queryUuid, String user);
}
