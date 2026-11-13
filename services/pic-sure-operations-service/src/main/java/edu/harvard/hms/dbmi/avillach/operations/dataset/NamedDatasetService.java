package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryVariantRow;

/**
 * Manages named datasets with user scoping enforced in SQL through {@code NamedDatasetRepository}'s
 * {@code findByUser}/{@code findByUuidAndUser}. The owner key is the caller's email, derived here from the {@link GatewayUser} via
 * {@link #requireEmail} (the identity-completeness check lives in this service, not the controller). Because the lookup is scoped at the
 * SQL layer, a caller reading/mutating another user's dataset gets exactly the same 404 as a genuinely-missing uuid -- there is no separate
 * 403 branch, and existence is never leaked to a non-owning caller (same posture as {@code ConfigurationService}'s not-found handling).
 *
 * <p>{@code archived} is not a list filter -- {@code findByUser} returns archived and non-archived rows alike; there is no soft-delete.
 *
 * <p>{@code queryId} is resolved to a persisted {@code Query} via {@code QueryRepository.findById}, returning 404 when absent.
 *
 * <p>A user can have several datasets on one query row. Nothing limits the pairs of query row and user, so naming a query a second time, or
 * moving a dataset onto a query another dataset of the user already points at, stores a second dataset. Dataset names are not unique
 * either.
 *
 * <p>A dataset points at one query row through {@code queryId}. {@link #create} sets it, and {@link #update} moves it when the request
 * names another query. A dataset is pointed only at a query row that belongs to the caller or has no owner. That check is made when
 * {@code queryId} is written and at no other time: a read, and an update that names the query the dataset already points at, do not look at
 * who owns that row.
 *
 * <p>A dataset also names a general query, in {@code queryToVariantUuid}. A general query is a user's query whatever its result type and
 * whether or not it is a sample, and every query row that has an owner is one variant of one. For a dataset on a query row that has an
 * owner it is the general query of that row: {@link #create} and {@link #update} copy the id from the row each time, so a dataset stored
 * without it takes it on its next update. A query row with no owner is under no general query. A dataset on such a row is created with
 * none, and {@link DatasetBringOver} files it under the one derived from the stored query for the user who reads it. {@link #update} keeps
 * that general query while the dataset stays on the row and clears it when the dataset is moved to another row with no owner, so the next
 * read derives it again.
 *
 * <p>Every answer lists the dataset's {@code queries}: the query rows that belong to the caller, by the gateway user id, under the general
 * query of the query the dataset points at. The general query stored on a dataset is not trusted for that, because a stack that does not
 * know the column moves {@code queryId} and leaves it. {@link #verifiedGeneralQuery} decides which general query is listed: the one of the
 * query row when that row is the caller's, the stored one when the row has no owner and the stored one is the general query derived for the
 * caller from the stored query, and none in every other case. Nothing links a query to a dataset. The stored body of the dataset's query is
 * read once for each dataset in an answer, and that one reading serves the answer, the check and {@link DatasetBringOver}.
 *
 * <p>The two reads, {@link #listForUser} and {@link #getForUser}, are the only callers of {@link DatasetBringOver}. Each reads in a
 * read-only transaction and answers from it. When {@link DatasetBringOver#isNotSettled} finds work for datasets that transaction loaded,
 * the read transaction ends first, {@link DatasetBringOver#settle} then handles each of them in a write transaction of its own, and when
 * that changed anything the read is made once more and answered from the new state. That step writes the general query of a dataset and
 * never its {@code queryId}. A caller whose gateway user id is not a UUID is never passed to that step, and with the step turned off, which
 * is the default, it has work for no dataset: a read is then one read-only transaction and writes nothing. A dataset the step cannot file
 * is answered as it was read. The two reads are called with no transaction open, as the controller calls them. Inside a caller's
 * transaction the read and the writes would all join it, and one dataset that fails would leave that whole transaction to roll back.
 */
@Service
public class NamedDatasetService {

    private static final Logger LOG = LoggerFactory.getLogger(NamedDatasetService.class);

    /** The bytes a MySQL {@code TEXT} column holds, which is the type of {@code named_dataset.metadata}. */
    static final int METADATA_MAX_BYTES = 65_535;

    private static final int MYSQL_INCORRECT_STRING_VALUE = 1366;

    /** The canonical text form of a UUID: 36 characters, hexadecimal digits in either case, hyphens after digits 8, 12, 16 and 20. */
    private static final Pattern CANONICAL_UUID =
        Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    private final NamedDatasetRepository repo;
    private final QueryRepository queryRepo;
    private final NamedDatasetMapper mapper;
    private final DatasetBringOver bringOver;
    private final TransactionTemplate reads;

    /**
     * Creates the service.
     *
     * @param repo the named datasets
     * @param queryRepo the query rows
     * @param mapper the mapper to the public shapes
     * @param bringOver the step that files the datasets a read loaded under the general query of their stored query
     * @param transactionManager the transaction manager the two reads open their read-only transactions on
     */
    public NamedDatasetService(
        NamedDatasetRepository repo, QueryRepository queryRepo, NamedDatasetMapper mapper, DatasetBringOver bringOver,
        PlatformTransactionManager transactionManager
    ) {
        this.repo = repo;
        this.queryRepo = queryRepo;
        this.mapper = mapper;
        this.bringOver = bringOver;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    /**
     * Lists the caller's named datasets, each with the variants of its general query that belong to the caller. Datasets of the caller that
     * {@link DatasetBringOver} has work for are filed under their general query first, as the class comment describes.
     *
     * @param user the caller
     * @return the caller's datasets, archived ones included
     * @throws PicsureException 401 when the caller has no email
     */
    public List<NamedDatasetDto> listForUser(GatewayUser user) {
        String email = requireEmail(user);
        return afterBringingOver(user, email, () -> repo.findByUser(email));
    }

    /**
     * Reads one of the caller's named datasets with the variants of its general query that belong to the caller. The dataset is filed under
     * its general query first when {@link DatasetBringOver} has work for it, as the class comment describes.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @return the dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id
     */
    public NamedDatasetDto getForUser(GatewayUser user, UUID id) {
        String email = requireEmail(user);
        return afterBringingOver(user, email, () -> List.of(repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id)))).getFirst();
    }

    /**
     * Answers a read. The datasets are loaded and turned into their public shape in one read-only transaction. When
     * {@link DatasetBringOver} has work for some of them, that work is done after the transaction has ended, and when it changed anything
     * the datasets are loaded and answered once more.
     *
     * @param user the caller
     * @param email the email the caller's datasets are stored under
     * @param load reads the datasets to answer with; called inside a read-only transaction, once or twice
     * @return the public shape of the datasets {@code load} returned last
     */
    private List<NamedDatasetDto> afterBringingOver(GatewayUser user, String email, Supplier<List<NamedDataset>> load) {
        Read first = reads.execute(status -> read(user, load.get()));
        if (first.notSettled().isEmpty() || !bringOver.settle(first.caller(), email, first.notSettled())) {
            return first.answer();
        }
        return reads.execute(status -> read(user, load.get())).answer();
    }

    /**
     * Builds the answer for the datasets a read loaded and names the ones {@link DatasetBringOver} has work for. The caller of this method
     * holds the read transaction.
     */
    private Read read(GatewayUser user, List<NamedDataset> datasets) {
        Optional<UUID> caller = uuidOf(user);
        List<Loaded> loaded = datasets.stream().map(dataset -> loaded(caller, dataset)).toList();
        List<UUID> notSettled = caller.isEmpty() ? List.of()
            : loaded.stream().filter(one -> bringOver.isNotSettled(caller.get(), one.dataset(), one.derivedGeneralQuery()))
                .map(one -> one.dataset().getUuid()).toList();
        return new Read(withVariants(caller, loaded), caller.orElse(null), notSettled);
    }

    /**
     * Reads the stored body of the query a dataset points at, once, and derives the caller's general query from it when the query row has
     * no owner. Nothing is read from the database.
     *
     * @throws PicsureException 500 when the stored body cannot be converted to v3
     */
    private Loaded loaded(Optional<UUID> caller, NamedDataset dataset) {
        Query row = dataset.getQuery();
        Optional<ObjectNode> storedRequest = mapper.storedRequestOf(row);
        boolean ownerless = row != null && row.getUserUuid() == null;
        Optional<UUID> derived =
            caller.isPresent() && ownerless ? DatasetBringOver.generalQueryFor(caller.get(), storedRequest) : Optional.empty();
        return new Loaded(dataset, storedRequest, derived);
    }

    /**
     * A dataset with what one reading of the stored body of its query gave.
     *
     * @param dataset the dataset
     * @param storedRequest the stored body as a v3 request with no consent values, or empty when the dataset points at no row or the row
     *        holds no body
     * @param derivedGeneralQuery the general query derived for the caller from that request, or empty when the query row has an owner, the
     *        caller has no UUID, or the request cannot be read as a query
     */
    private record Loaded(NamedDataset dataset, Optional<ObjectNode> storedRequest, Optional<UUID> derivedGeneralQuery) {
    }

    /**
     * What one read transaction found.
     *
     * @param answer the public shape of the datasets
     * @param caller the caller's UUID, or null when the gateway user id is not one
     * @param notSettled the ids of the datasets {@link DatasetBringOver} has work for; empty when the caller has no UUID
     */
    private record Read(List<NamedDatasetDto> answer, UUID caller, List<UUID> notSettled) {
    }

    /**
     * Names a query as a dataset of the caller. The dataset's {@code queryId} is set to the query. The dataset is filed under the general
     * query of that query row when the row has an owner, and under none when it has no owner. The query row must belong to the caller, by
     * the gateway user id, or have no owner. That is checked before anything is saved, so a refused request stores nothing.
     *
     * @param user the caller
     * @param req the query to name and the name, archived state and metadata to store
     * @return the created dataset
     * @throws PicsureException 401 when the caller has no email, 404 when no query has the id, 403 when the query belongs to another user,
     *         400 when the metadata is too long or the database refuses the dataset
     */
    @Transactional
    public NamedDatasetDto create(GatewayUser user, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        requireMetadataThatFits(req.metadata());
        Query query = resolveQuery(req.queryId());
        requireOwnedByCallerOrOwnerless(user, query);
        NamedDataset created = mapper.toEntity(email, query, req).setQueryToVariantUuid(generalQueryOfAnOwnedRow(query));
        return answer(user, saved(created));
    }

    /**
     * Updates the name, archived state and metadata of one of the caller's datasets, and points the dataset at the query the request names
     * when that is not the query it points at already.
     *
     * <p>The query to move to must belong to the caller, by the gateway user id, or have no owner. A request that names another user's
     * query is refused before anything is changed. A request that names the query the dataset already points at moves nothing and makes no
     * owner check.
     *
     * <p>The general query of the dataset follows the query row it ends up on. A row that has an owner gives the dataset its own general
     * query. A row with no owner that the dataset is moved onto leaves the dataset with no general query, and the next read derives one. A
     * row with no owner that the dataset was on already leaves the general query of the dataset as it is, so a rename or an archive keeps
     * it.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @param req the query to point at and the name, archived state and metadata to store
     * @return the updated dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id or no query has the id to
     *         move to, 403 when the query to move to belongs to another user, 400 when the metadata is too long or the database refuses the
     *         dataset
     */
    @Transactional
    public NamedDatasetDto update(GatewayUser user, UUID id, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        requireMetadataThatFits(req.metadata());
        NamedDataset existing = repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id));
        boolean moved = existing.getQuery() == null || !existing.getQuery().getUuid().equals(req.queryId());
        if (moved) {
            Query query = resolveQuery(req.queryId());
            requireOwnedByCallerOrOwnerless(user, query);
            existing.setQuery(query);
        }
        Query row = existing.getQuery();
        if (row.getUserUuid() != null || moved) {
            existing.setQueryToVariantUuid(generalQueryOfAnOwnedRow(row));
        }
        existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
        return answer(user, saved(existing));
    }

    /**
     * Deletes one of the caller's named datasets. The query it points at and the other variants of its general query stay.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id
     */
    @Transactional
    public void delete(GatewayUser user, UUID id) {
        NamedDataset existing = repo.findByUuidAndUser(id, requireEmail(user)).orElseThrow(() -> notFound(id));
        repo.delete(existing);
    }

    /**
     * Saves a dataset and writes it to the database now, so that a value the database refuses is answered here. The sizes of the name and
     * the metadata are checked before this point. What is left is a value only the database can judge, such as a character the table's
     * character set cannot store or a caller email longer than its column.
     *
     * @throws PicsureException 400 when the database refuses a value of the row. The database's own message is logged and not sent to the
     *         caller
     * @throws DataAccessException when the save fails for any other reason
     */
    private NamedDataset saved(NamedDataset dataset) {
        try {
            return repo.saveAndFlush(dataset);
        } catch (DataAccessException failed) {
            if (!isARefusedValue(failed)) {
                throw failed;
            }
            LOG.warn("The database refused a named dataset: {}", failed.getMostSpecificCause().getMessage());
            throw PicsureExceptions.badRequest("The dataset could not be saved with these values");
        }
    }

    /**
     * Tells whether a failed save means the database refused a value of the row: an integrity violation, which covers a value too long for
     * its column, or MySQL error 1366, which MySQL raises for a character the column's character set cannot store and which is not reported
     * as an integrity violation.
     */
    private static boolean isARefusedValue(DataAccessException failed) {
        if (failed instanceof DataIntegrityViolationException) {
            return true;
        }
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getErrorCode() == MYSQL_INCORRECT_STRING_VALUE;
            }
        }
        return false;
    }

    /**
     * Refuses metadata that would not fit its column. The metadata is stored as JSON text in a {@code TEXT} column, which holds 65,535
     * bytes, so the JSON is written the way {@link JsonConverter} stores it and its UTF-8 bytes are counted.
     *
     * @throws PicsureException 400 when the JSON takes more bytes than the column holds
     */
    private static void requireMetadataThatFits(Map<String, Object> metadata) {
        String stored = new JsonConverter().convertToDatabaseColumn(metadata);
        if (stored != null && stored.getBytes(StandardCharsets.UTF_8).length > METADATA_MAX_BYTES) {
            throw PicsureExceptions.badRequest("The metadata is too long: as JSON it may take at most " + METADATA_MAX_BYTES + " bytes");
        }
    }

    /** Builds the public shape of one dataset a create or an update saved, with its {@code queries}. */
    private NamedDatasetDto answer(GatewayUser user, NamedDataset dataset) {
        Optional<UUID> caller = uuidOf(user);
        return withVariants(caller, List.of(loaded(caller, dataset))).getFirst();
    }

    /**
     * Builds the public shape of each dataset with its {@code queries}: the caller's query rows under the dataset's verified general query,
     * in the order {@link QueryRepository#findVariantsOf} returns them. One query fetches the rows of all the datasets. A dataset with no
     * verified general query lists none, and so does every dataset when the caller's gateway user id is not a UUID.
     *
     * @param caller the caller's UUID, or empty when the gateway user id is not one
     * @param datasets the datasets to show, each with the stored body of its query read
     * @return the datasets in the order given
     */
    private List<NamedDatasetDto> withVariants(Optional<UUID> caller, List<Loaded> datasets) {
        List<Optional<UUID>> generalQueries = datasets.stream().map(one -> verifiedGeneralQuery(caller, one)).toList();
        Map<UUID, List<LinkedQueryDto>> variants = callersVariantsByGeneralQuery(caller, generalQueries);
        List<NamedDatasetDto> answer = new ArrayList<>();
        for (int index = 0; index < datasets.size(); index++) {
            Loaded one = datasets.get(index);
            List<LinkedQueryDto> queries =
                generalQueries.get(index).map(general -> variants.getOrDefault(general, List.of())).orElse(List.of());
            answer.add(mapper.toDto(one.dataset(), one.storedRequest(), queries));
        }
        return List.copyOf(answer);
    }

    /**
     * Returns the general query whose variants a dataset lists for the caller, which is the general query of the query the dataset points
     * at now. The value stored on the dataset is used only when it is proven to be that one.
     *
     * @param caller the caller's UUID, or empty when the gateway user id is not one
     * @param one the dataset with the general query derived for the caller from its stored query
     * @return the general query of the query row when that row belongs to the caller; the general query stored on the dataset when the row
     *         has no owner and the stored value equals the derived one; empty when the caller has no UUID, the dataset points at no row,
     *         the row belongs to another user, the stored query cannot be read, or the stored value is another general query
     */
    private static Optional<UUID> verifiedGeneralQuery(Optional<UUID> caller, Loaded one) {
        Query row = one.dataset().getQuery();
        if (caller.isEmpty() || row == null) {
            return Optional.empty();
        }
        if (row.getUserUuid() == null) {
            return one.derivedGeneralQuery().filter(derived -> derived.equals(one.dataset().getQueryToVariantUuid()));
        }
        return row.getUserUuid().equals(caller.get()) ? Optional.ofNullable(row.getQueryToVariantUuid()) : Optional.empty();
    }

    private Map<UUID, List<LinkedQueryDto>> callersVariantsByGeneralQuery(Optional<UUID> caller, List<Optional<UUID>> verified) {
        Set<UUID> generalQueries = verified.stream().flatMap(Optional::stream).collect(Collectors.toCollection(LinkedHashSet::new));
        if (caller.isEmpty() || generalQueries.isEmpty()) {
            return Map.of();
        }
        return queryRepo.findVariantsOf(generalQueries, caller.get()).stream().collect(
            Collectors.groupingBy(
                QueryVariantRow::queryToVariantUuid,
                Collectors.mapping(row -> new LinkedQueryDto(row.uuid(), row.sample(), row.resultType()), Collectors.toList())
            )
        );
    }

    /**
     * Returns the general query a dataset on the given query row takes from that row.
     *
     * @param row the query row a dataset points at
     * @return the general query of the row when the row has an owner, and null when it has none
     */
    private static UUID generalQueryOfAnOwnedRow(Query row) {
        return row.getUserUuid() == null ? null : row.getQueryToVariantUuid();
    }

    /**
     * The owner of a query row is a user UUID, and the caller is the gateway user id. A row with no owner can be named by anyone, because
     * rows stored before queries had owners have none.
     *
     * @throws PicsureException 403 when the row has an owner and the caller's gateway user id is not that owner's UUID
     */
    private static void requireOwnedByCallerOrOwnerless(GatewayUser user, Query query) {
        UUID owner = query.getUserUuid();
        if (owner != null && !owner.equals(uuidOf(user).orElse(null))) {
            throw PicsureExceptions.forbidden("Query " + query.getUuid() + " belongs to another user");
        }
    }

    /**
     * Returns the caller's UUID when the gateway user id is a UUID in its canonical text form, and empty otherwise. Letter case is not part
     * of the identity: an id in upper case names the same user as its lower case form. A caller the gateway identifies some other way, such
     * as by an API key, has no UUID and so owns no query row.
     */
    private static Optional<UUID> uuidOf(GatewayUser user) {
        String id = user.getUserId();
        if (id == null || !CANONICAL_UUID.matcher(id).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(id));
    }

    /**
     * Returns the caller's email owner key. {@code WebSecurityConfig} already rejects unauthenticated requests to {@code /dataset/**}
     * before any controller method runs, so this is a defensive guard against a gateway that authenticated the caller (sent
     * {@code X-User-Id}) but omitted {@code X-User-Email} -- not the primary auth gate.
     */
    private static String requireEmail(GatewayUser user) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw PicsureExceptions.unauthorized("User identity (email) not present in request");
        }
        return user.getEmail();
    }

    private Query resolveQuery(UUID queryId) {
        return queryRepo.findById(queryId).orElseThrow(() -> PicsureExceptions.notFound("Query", queryId));
    }

    private static PicsureException notFound(UUID id) {
        return PicsureExceptions.notFound("NamedDataset", id);
    }
}
