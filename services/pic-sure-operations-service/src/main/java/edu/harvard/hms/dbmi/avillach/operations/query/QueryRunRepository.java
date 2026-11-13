package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the runs of a query. Runs are inserted and completed, never deleted. */
public interface QueryRunRepository extends JpaRepository<QueryRun, UUID> {

    /**
     * Finds the run of a query that was submitted last.
     *
     * @param queryUuid the id of the query row
     * @return the latest run, or empty when the query has none
     */
    Optional<QueryRun> findFirstByQueryUuidOrderBySubmittedAtDesc(UUID queryUuid);

    /**
     * Lists every run of a query, oldest first.
     *
     * @param queryUuid the id of the query row
     * @return the runs in submit order
     */
    List<QueryRun> findByQueryUuidOrderBySubmittedAtAsc(UUID queryUuid);
}
