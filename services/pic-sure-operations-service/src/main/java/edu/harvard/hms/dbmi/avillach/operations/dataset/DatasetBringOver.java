package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.QueryIdentity;
import edu.harvard.hms.dbmi.avillach.operations.query.NeverRunQuery;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryPersistenceService;

/**
 * Brings a caller's named datasets over to query rows the caller owns, when the caller reads them.
 *
 * <p>A dataset saved before query rows had owners points at a row with no owner. Such a row is under no general query, so the dataset lists
 * no variants. The ids of an owned row and of its general query are derived from the owner's UUID, and a dataset stores its user only as an
 * email, so nothing but a request of that user can supply the UUID. {@link NamedDatasetService} therefore calls this class from its two
 * reads, and from nowhere else, with the UUID the gateway sent for the caller.
 *
 * <p><b>Which datasets.</b> {@link #notSettled} looks at datasets a read has loaded and names the ones there is work for. It reads nothing
 * from the database. <ul> <li>A dataset whose query row has no owner is brought over.</li> <li>A dataset whose query row belongs to the
 * caller, and whose general query is not the one of that row, takes the general query of the row.</li> <li>Every other dataset is left: one
 * that points at no row, one that already has the general query of its owned row, and one whose row belongs to another user.</li> </ul>
 *
 * <p><b>Bringing one over.</b> {@link #settle} handles each dataset in a write transaction of its own. The caller calls it after its read
 * transaction has ended. The dataset row is locked first and the cases above are decided again on the locked row, so two requests that read
 * one dataset at the same time bring it over once. For a row with no owner: <ol> <li>The stored body is read as a v3 request with no
 * consent values, translated first when it was stored before v3. Its query goes through the v3 query record, which drops every member the
 * record does not have.</li> <li>The row id and the general id are derived from the caller's UUID and that query with
 * {@link QueryIdentity}. They are the ids the query service derives when the caller submits the same query, so a later submit of the
 * dataset's query lands on the row stored here.</li> <li>When the caller already has another dataset on the row with that id, the dataset
 * is left as it is and nothing is written, because one user cannot have two datasets on one query row.</li> <li>Otherwise
 * {@link QueryPersistenceService#storeNeverRun} stores the general query and the owned row unless they are stored already, and the dataset
 * is pointed at the row and filed under its general query.</li> </ol> The row the dataset pointed at before is never written. It keeps its
 * id, its body and its lack of an owner.
 *
 * <p>The new row has no status, no consents and no run. The query service runs it the first time the caller asks for its status or result
 * or submits the query again. Its start time is the one of the old row, so the dataset keeps its creation date.
 *
 * <p><b>Failures.</b> A dataset that cannot be brought over stays as it was: its transaction is rolled back, the dataset id and the class
 * of the failure are logged, and the next dataset is handled. Nothing a read answers depends on this step succeeding. A dataset that fails
 * every time, such as one whose stored body cannot be read as a query, is tried again on every read.
 *
 * <p><b>Off by default.</b> With {@code picsure.operations.dataset-bring-over.enabled} left at false, {@link #notSettled} names no dataset
 * and no read writes anything. A dataset that was brought over names a row a stack without query owners cannot read, so the setting is
 * turned on once no such stack reads the database any more.
 *
 * <p>The log lines carry dataset and query ids only: no email, and nothing of a query.
 */
@Component
@EnableConfigurationProperties(DatasetBringOverProperties.class)
public class DatasetBringOver {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetBringOver.class);
    private static final ObjectMapper QUERY_MAPPER =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final String QUERY = "query";

    private final NamedDatasetRepository datasets;
    private final QueryPersistenceService queries;
    private final TransactionTemplate writes;
    private final DatasetBringOverProperties properties;

    /**
     * Creates the step.
     *
     * @param datasets the named datasets
     * @param queries the service that stores the owned query rows and their general queries
     * @param transactionManager the transaction manager the write transaction of each dataset is opened on
     * @param properties whether the step is turned on
     */
    public DatasetBringOver(
        NamedDatasetRepository datasets, QueryPersistenceService queries, PlatformTransactionManager transactionManager,
        DatasetBringOverProperties properties
    ) {
        this.datasets = datasets;
        this.queries = queries;
        this.writes = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    /**
     * Names the datasets among the given ones that {@link #settle} has work for. Only the entities are looked at, and nothing is read from
     * the database, so a caller whose datasets are all settled costs no query.
     *
     * @param caller the UUID the gateway sent for the caller
     * @param loaded datasets of the caller, each with the query row it points at loaded
     * @return the ids of the datasets to pass to {@link #settle}, in the order given; empty when the step is turned off
     */
    public List<UUID> notSettled(UUID caller, List<NamedDataset> loaded) {
        if (!properties.enabled()) {
            return List.of();
        }
        return loaded.stream().filter(dataset -> workFor(caller, dataset) != Work.NONE).map(NamedDataset::getUuid).toList();
    }

    /**
     * Brings each of the given datasets over, one write transaction for each. Call it outside a transaction, after the read that loaded the
     * datasets has ended, so that each dataset commits or rolls back by itself. A dataset that fails is logged and skipped, and this method
     * does not throw for it.
     *
     * @param caller the UUID the gateway sent for the caller
     * @param user the email the datasets are stored under
     * @param datasetIds the ids {@link #notSettled} returned
     * @return true when at least one dataset is no longer as the read saw it, because this call changed it or another request did, so that
     *         the read has to be made again
     */
    public boolean settle(UUID caller, String user, List<UUID> datasetIds) {
        boolean changed = false;
        for (UUID datasetId : datasetIds) {
            changed |= settledInItsOwnTransaction(caller, user, datasetId);
        }
        return changed;
    }

    /**
     * Handles one dataset in a transaction of its own and logs what was committed. A failure, of the work or of the commit, is logged and
     * not thrown.
     *
     * @return true when the read has to be made again
     */
    private boolean settledInItsOwnTransaction(UUID caller, String user, UUID datasetId) {
        Outcome outcome;
        try {
            outcome = writes.execute(status -> settleLocked(caller, user, datasetId));
        } catch (RuntimeException failed) {
            LOG.warn("Could not bring dataset {} over: {}", datasetId, failed.getClass().getName());
            return false;
        }
        if (outcome.to() != null && outcome.to().equals(outcome.from())) {
            LOG.info("Filed dataset {} under the general query of its query {}", datasetId, outcome.to());
        } else if (outcome.to() != null) {
            LOG.info("Brought dataset {} over from query {} to query {}", datasetId, outcome.from(), outcome.to());
        }
        return outcome.readAgain();
    }

    /**
     * Locks one dataset and does the work it still needs. The caller of this method holds the transaction.
     *
     * @return what was done. The read has to be made again when the dataset was written, was deleted since the read, or needs no work any
     *         more because another request did it, and not when the dataset was left as the read saw it
     */
    private Outcome settleLocked(UUID caller, String user, UUID datasetId) {
        Optional<NamedDataset> locked = datasets.findLockedByUuidAndUser(datasetId, user);
        if (locked.isEmpty()) {
            return Outcome.CHANGED_BY_ANOTHER_REQUEST;
        }
        NamedDataset dataset = locked.get();
        return switch (workFor(caller, dataset)) {
            case NONE -> Outcome.CHANGED_BY_ANOTHER_REQUEST;
            case TAKE_THE_GENERAL_QUERY_OF_ITS_ROW -> takeTheGeneralQueryOfItsRow(dataset);
            case BRING_OVER -> bringOver(caller, dataset);
        };
    }

    private static Work workFor(UUID caller, NamedDataset dataset) {
        Query row = dataset.getQuery();
        if (row == null) {
            return Work.NONE;
        }
        if (row.getUserUuid() == null) {
            return Work.BRING_OVER;
        }
        boolean underTheGeneralQueryOfItsRow = Objects.equals(dataset.getQueryToVariantUuid(), row.getQueryToVariantUuid());
        return row.getUserUuid().equals(caller) && !underTheGeneralQueryOfItsRow ? Work.TAKE_THE_GENERAL_QUERY_OF_ITS_ROW : Work.NONE;
    }

    private Outcome takeTheGeneralQueryOfItsRow(NamedDataset dataset) {
        Query row = dataset.getQuery();
        datasets.saveAndFlush(dataset.setQueryToVariantUuid(row.getQueryToVariantUuid()));
        return new Outcome(true, row.getUuid(), row.getUuid());
    }

    /**
     * Points a dataset whose query row has no owner at a row the caller owns, storing that row and its general query when they are not
     * stored yet.
     *
     * @return the row the dataset pointed at and the owned row it points at now, or {@link Outcome#LEFT} when the dataset was left because
     *         its row holds no body, or because the caller already has another dataset on the owned row
     * @throws IllegalArgumentException when the stored body cannot be read as a query
     */
    private Outcome bringOver(UUID caller, NamedDataset dataset) {
        Query ownerless = dataset.getQuery();
        Optional<ObjectNode> stored = storedRequestWithoutConsentValues(ownerless);
        if (stored.isEmpty()) {
            return Outcome.LEFT;
        }
        ObjectNode request = stored.get();
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query query = v3QueryOf(request);
        UUID id = QueryIdentity.idFor(caller, query);
        if (datasets.existsByQueryUuidAndUser(id, dataset.getUser())) {
            return Outcome.LEFT;
        }
        request.set(QUERY, QUERY_MAPPER.valueToTree(query.setPicsureId(id)));
        Query owned = queries.storeNeverRun(
            new NeverRunQuery(
                id, caller, QueryIdentity.generalIdFor(caller, query), query.sample(), query.expectedResultType(), request.toString(),
                ownerless.getStartTime()
            )
        );
        datasets.saveAndFlush(dataset.setQuery(owned).setQueryToVariantUuid(owned.getQueryToVariantUuid()));
        return new Outcome(true, ownerless.getUuid(), id);
    }

    private static Optional<ObjectNode> storedRequestWithoutConsentValues(Query row) {
        try {
            return NamedDatasetMapper.strippedV3Request(row);
        } catch (JsonProcessingException | UntranslatableQueryException unreadable) {
            throw new IllegalArgumentException("The stored body cannot be read as a query");
        }
    }

    /**
     * Reads the query of a request through the v3 query record, which keeps the members the record has and drops every other one, at any
     * depth. The query service reads a stored body of an owned row with a mapper that refuses unknown members.
     *
     * @param request a v3 request wrapper
     * @return the query, with no authorization filters
     * @throws IllegalArgumentException when the query does not have the member types of a v3 query
     */
    private static edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query v3QueryOf(ObjectNode request) {
        try {
            return QUERY_MAPPER.treeToValue(request.get(QUERY), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class)
                .setAuthorizationFilters(List.of());
        } catch (JsonProcessingException notAV3Query) {
            throw new IllegalArgumentException("The stored body cannot be read as a query");
        }
    }

    /**
     * What the write transaction of one dataset did.
     *
     * @param readAgain whether the dataset is no longer as the read saw it
     * @param from the id of the query row the dataset pointed at before this transaction wrote it, or null when it wrote nothing
     * @param to the id of the query row the dataset points at now, or null when this transaction wrote nothing; the same as {@code from}
     *        when only the general query of the dataset was written
     */
    private record Outcome(boolean readAgain, UUID from, UUID to) {

        /** The dataset was left as the read saw it. */
        static final Outcome LEFT = new Outcome(false, null, null);

        /** Nothing was written, and the dataset is gone or needs no work any more, so another request changed it since the read. */
        static final Outcome CHANGED_BY_ANOTHER_REQUEST = new Outcome(true, null, null);
    }

    /** What {@link #settle} has to do for one dataset. */
    private enum Work {
        /** Nothing: the dataset points at no row, is settled, or points at a row of another user. */
        NONE,
        /** The dataset points at a row of the caller and is not filed under the general query of that row. */
        TAKE_THE_GENERAL_QUERY_OF_ITS_ROW,
        /** The dataset points at a row with no owner. */
        BRING_OVER
    }
}
