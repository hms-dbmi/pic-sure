package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data repository for the join rows between named datasets and queries. */
public interface NamedDatasetQueryRepository extends JpaRepository<NamedDatasetQuery, NamedDatasetQuery.Key> {

    /**
     * Lists the join rows of the given named datasets, the earliest linked first. Rows linked at the same instant come in the order of
     * their query ids, so the order is the same on every call.
     *
     * @param datasets the ids of the named datasets; must not be empty
     * @return the join rows of all of them in one list
     */
    @Query(
        "select link from named_dataset_query link where link.id.namedDatasetUuid in :datasets order by link.createdAt, link.id.queryUuid"
    )
    List<NamedDatasetQuery> findByDatasets(@Param("datasets") Collection<UUID> datasets);

    /**
     * Sets the result type of a join row that has none. A row that already has one is left alone.
     *
     * <p>The statement runs in its own transaction when the caller holds none. It fails on the variant key when another row of the same
     * dataset already has the same {@code sample} value and this result type.
     *
     * @param dataset the id of the named dataset
     * @param query the id of the linked query
     * @param resultType the result type to store
     * @return the number of rows changed, 0 or 1
     * @throws org.springframework.dao.DataIntegrityViolationException when the variant key refuses the value
     */
    @Transactional
    @Modifying
    @Query(
        "update named_dataset_query link set link.resultType = :resultType"
            + " where link.id.namedDatasetUuid = :dataset and link.id.queryUuid = :query and link.resultType is null"
    )
    int fillResultType(@Param("dataset") UUID dataset, @Param("query") UUID query, @Param("resultType") String resultType);
}
