package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.io.UncheckedIOException;
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
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryPersistenceService;

/**
 * Files a caller's named datasets under the general query of their stored query, when the caller reads them.
 *
 * <p>A dataset saved before query rows had owners points at a row with no owner. Such a row is under no general query, so the dataset lists
 * no variants. The id of a general query is derived from the owner's UUID, and a dataset stores its user only as an email, so nothing but a
 * request of that user can supply the UUID. {@link NamedDatasetService} therefore calls this class from its two reads, and from nowhere
 * else, with the UUID the gateway sent for the caller.
 *
 * <p><b>What the step writes.</b> The {@code query_to_variant_uuid} column of a dataset, and a {@code query_to_variant} row when the one it
 * needs is not stored yet. It never inserts or changes a {@code query} row and never writes the {@code queryId} of a dataset, so the id a
 * user holds for a dataset stays what it was. A stack that does not know the column reads and writes such a dataset as before.
 *
 * <p><b>Which datasets.</b> {@link #isNotSettled} looks at a dataset a read has loaded and tells whether there is work for it. <ul> <li>A
 * dataset whose query row has no owner should be under the general query derived for the caller from the stored query of that row. There is
 * work for it when it is under none, or under another one that belongs to the caller.</li> <li>A dataset whose query row belongs to the
 * caller should be under the general query of that row, and there is work for it when it is not.</li> <li>Every other dataset is left: one
 * that points at no row, one whose row belongs to another user, one whose stored query cannot be read, and one that is under a general
 * query of another user.</li> </ul> Only the last case reads the database, with one plain read of the general query the dataset is under.
 * Every other case is decided from the entities, so a caller whose datasets are all settled costs no query.
 *
 * <p><b>Deriving the general query.</b> {@link #generalQueryFor} takes the stored body as a v3 request with no consent values, translated
 * first when it was stored before v3. Its query goes through the v3 query record, which drops every member the record does not have, and
 * {@link QueryIdentity#generalIdFor} gives the id for the caller. It is the id the query service derives when the caller submits the same
 * query, whatever the result type and whether or not it is a sample, so every row a later submit saves is found under the dataset. The
 * derivation reads no database and is not gated by the setting: {@link NamedDatasetService} uses it on every read to check the general
 * query of a dataset before it lists variants under it.
 *
 * <p><b>Filing one.</b> {@link #settle} handles each dataset in a write transaction of its own. The caller calls it after its read
 * transaction has ended. The dataset row is locked first and the cases above are decided again on the locked row, so two requests that read
 * one dataset at the same time file it once. For a row with no owner: <ul> <li>A dataset under no general query is filed under the derived
 * one, which {@link QueryPersistenceService#ensureGeneralQuery} stores first unless it is stored already.</li> <li>A dataset under another
 * general query is filed under the derived one only when that other general query belongs to the caller. That is a dataset whose
 * {@code queryId} was moved by a stack that does not know the column, which left the general query of the query it pointed at before.</li>
 * <li>A dataset under a general query of another user is left.</li> </ul>
 *
 * <p><b>Two users with one email.</b> A dataset is stored under an email, so two users who have the same email share their datasets, and a
 * dataset holds one general query. The user who reads a dataset first files it under their general query and keeps it: a read by the second
 * user changes nothing and lists no variants for that user. The general query is cleared when a {@code PUT} moves the dataset to another
 * query row with no owner, and whichever of the two users reads the dataset next then files it under theirs.
 *
 * <p><b>Failures.</b> A dataset that cannot be filed stays as it was: its transaction is rolled back, the dataset id and the class of the
 * failure are logged, and the next dataset is handled. Nothing a read answers depends on this step succeeding. A dataset that is left or
 * that fails is looked at again on every read.
 *
 * <p><b>Stored bodies that cannot be read as a query.</b> Such a dataset is left as it is. {@link NamedDatasetMapper} reads the same body
 * for the answer of the read and fails the whole read with 500, so nothing is written on that request either.
 *
 * <p><b>Off by default.</b> With {@code picsure.operations.dataset-bring-over.enabled} left at false, {@link #isNotSettled} finds work for
 * no dataset and no read writes anything.
 *
 * <p>The log lines carry dataset, query and general query ids only: no email, and nothing of a query.
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
     * @param queries the service that stores the general queries
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
     * Tells whether {@link #settle} has work for a dataset a read has loaded. Nothing is read from the database, except for a dataset on a
     * row with no owner that is under a general query other than the derived one: the owner of that general query is then looked up with
     * one plain read, and there is work only when it is the caller. No lock is taken and no transaction is opened.
     *
     * @param caller the UUID the gateway sent for the caller
     * @param dataset a dataset of the caller, with the query row it points at loaded
     * @param derivedGeneralQuery what {@link #generalQueryFor} returned for the caller and the stored query of the dataset's row; only
     *        looked at when that row has no owner
     * @return true when the id of the dataset is to be passed to {@link #settle}; false when the step is turned off
     */
    public boolean isNotSettled(UUID caller, NamedDataset dataset, Optional<UUID> derivedGeneralQuery) {
        Query row = dataset.getQuery();
        if (!properties.enabled() || row == null) {
            return false;
        }
        if (row.getUserUuid() != null) {
            return row.getUserUuid().equals(caller) && !isUnderTheGeneralQueryOfItsRow(dataset);
        }
        UUID filedUnder = dataset.getQueryToVariantUuid();
        if (derivedGeneralQuery.isEmpty() || derivedGeneralQuery.get().equals(filedUnder)) {
            return false;
        }
        return filedUnder == null || belongsTo(caller, filedUnder);
    }

    /**
     * Derives the id of the general query a caller's submit of a stored query is filed under. The query of the request is read through the
     * v3 query record, which keeps the members the record has and drops every other one, at any depth. Nothing is read from the database
     * and the request is not changed.
     *
     * @param caller the UUID of the user the general query is derived for
     * @param storedRequest the stored body of a query row as {@link NamedDatasetMapper#strippedV3Request} returns it: a v3 request with no
     *        consent values, or empty for a row that holds no body
     * @return the id, or empty when there is no request or its query cannot be read as a v3 query
     */
    public static Optional<UUID> generalQueryFor(UUID caller, Optional<ObjectNode> storedRequest) {
        if (storedRequest.isEmpty()) {
            return Optional.empty();
        }
        try {
            var query =
                QUERY_MAPPER.treeToValue(storedRequest.get().get(QUERY), edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class)
                    .setAuthorizationFilters(List.of());
            return Optional.of(QueryIdentity.generalIdFor(caller, query));
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Files each of the given datasets, one write transaction for each. Call it outside a transaction, after the read that loaded the
     * datasets has ended, so that each dataset commits or rolls back by itself. A dataset that fails is logged and skipped, and this method
     * does not throw for it.
     *
     * @param caller the UUID the gateway sent for the caller
     * @param user the email the datasets are stored under
     * @param datasetIds the ids of the datasets {@link #isNotSettled} found work for
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
        if (outcome.ownedRow() != null) {
            LOG.info("Filed dataset {} under the general query of its query {}", datasetId, outcome.ownedRow());
        } else if (outcome.derivedGeneralQuery() != null) {
            LOG.info("Brought dataset {} over to general query {}", datasetId, outcome.derivedGeneralQuery());
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
        Query row = dataset.getQuery();
        if (row != null && row.getUserUuid() == null) {
            return fileUnderTheDerivedGeneralQuery(caller, dataset);
        }
        if (row == null || !row.getUserUuid().equals(caller) || isUnderTheGeneralQueryOfItsRow(dataset)) {
            return Outcome.CHANGED_BY_ANOTHER_REQUEST;
        }
        datasets.saveAndFlush(dataset.setQueryToVariantUuid(row.getQueryToVariantUuid()));
        return Outcome.tookTheGeneralQueryOf(row.getUuid());
    }

    private boolean belongsTo(UUID caller, UUID generalQuery) {
        return queries.ownerOfGeneralQuery(generalQuery).filter(caller::equals).isPresent();
    }

    private static boolean isUnderTheGeneralQueryOfItsRow(NamedDataset dataset) {
        return Objects.equals(dataset.getQueryToVariantUuid(), dataset.getQuery().getQueryToVariantUuid());
    }

    /**
     * Files a locked dataset whose query row has no owner under the general query derived for the caller from the stored query of that row.
     * The general query is stored first when it is not stored yet. Neither the query row nor the {@code queryId} of the dataset is written.
     *
     * @return the general query the dataset is filed under now; {@link Outcome#LEFT} when the stored query cannot be read or the dataset is
     *         under a general query that is not the caller's; {@link Outcome#CHANGED_BY_ANOTHER_REQUEST} when the dataset is already under
     *         the derived general query
     */
    private Outcome fileUnderTheDerivedGeneralQuery(UUID caller, NamedDataset dataset) {
        Optional<UUID> derived = derivedGeneralQuery(caller, dataset.getQuery());
        if (derived.isEmpty()) {
            return Outcome.LEFT;
        }
        UUID filedUnder = dataset.getQueryToVariantUuid();
        if (derived.get().equals(filedUnder)) {
            return Outcome.CHANGED_BY_ANOTHER_REQUEST;
        }
        if (filedUnder != null && !belongsTo(caller, filedUnder)) {
            return Outcome.LEFT;
        }
        datasets.saveAndFlush(dataset.setQueryToVariantUuid(queries.ensureGeneralQuery(derived.get(), caller)));
        return Outcome.broughtOverTo(derived.get());
    }

    /**
     * Reads the stored body of a locked dataset's row again and derives the general query from it.
     *
     * @return the id, or empty when the row holds no body or the body cannot be read as a query
     */
    private static Optional<UUID> derivedGeneralQuery(UUID caller, Query row) {
        try {
            return generalQueryFor(caller, NamedDatasetMapper.strippedV3Request(row));
        } catch (JsonProcessingException | UntranslatableQueryException | IllegalArgumentException | UncheckedIOException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * What the write transaction of one dataset did.
     *
     * @param readAgain whether the dataset is no longer as the read saw it
     * @param derivedGeneralQuery the id of the derived general query the dataset was filed under, or null when this transaction did not
     *        file it under one
     * @param ownedRow the id of the caller's query row whose general query the dataset took, or null when this transaction did not do that
     */
    private record Outcome(boolean readAgain, UUID derivedGeneralQuery, UUID ownedRow) {

        /** The dataset was left as the read saw it. */
        static final Outcome LEFT = new Outcome(false, null, null);

        /** Nothing was written, and the dataset is gone or needs no work any more, so another request changed it since the read. */
        static final Outcome CHANGED_BY_ANOTHER_REQUEST = new Outcome(true, null, null);

        /** The dataset was filed under the general query derived from the stored query of its row. */
        static Outcome broughtOverTo(UUID derivedGeneralQuery) {
            return new Outcome(true, derivedGeneralQuery, null);
        }

        /** The dataset was filed under the general query of the caller's row it points at. */
        static Outcome tookTheGeneralQueryOf(UUID ownedRow) {
            return new Outcome(true, null, ownedRow);
        }
    }
}
