package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.dataset.NamedDatasetMapper.QueryVariant;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

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
 * <p>{@code NamedDataset} carries a DB-level unique constraint on {@code (queryId, user)}. Rather than a check-then-save pre-check (which
 * would race under concurrent requests), {@link #create} and {@link #update} (when it sets the query) use {@code saveAndFlush} inside a
 * try/catch so the constraint violation surfaces synchronously and is translated to {@code 409 CONFLICT} via {@link PicsureException},
 * mirroring how {@code ConfigurationService} handles its name+kind conflict.
 *
 * <p>A dataset links any number of queries through {@code named_dataset_query} rows, at most one for each variant, and a variant is the
 * {@code sample} flag together with the result type. {@code named_dataset.queryId} is still written. {@link #create} sets it and adds a
 * join row for the same query. {@link #update} adds a join row when the request names a query the dataset does not link yet, and moves
 * {@code queryId} only when the dataset has none and the new query is not a sample, so it stays on the query the dataset was created from.
 * A dataset the previous release created has no join row, so the query its {@code queryId} names always counts as linked.
 *
 * <p>The join rows the migration created have no result type. A read derives it from the stored query body and, after its own transaction
 * has ended, stores it on the row. The variant key can refuse that write when another row of the dataset already has the same variant. The
 * read then leaves the row as it is and still answers with the derived type.
 *
 * <p>None of the public methods is transactional. Each runs its reads and writes in one transaction of its own, and stores derived result
 * types afterward, one statement at a time, so a refused statement undoes nothing else.
 */
@Service
public class NamedDatasetService {

    private static final Logger LOG = LoggerFactory.getLogger(NamedDatasetService.class);

    private final NamedDatasetRepository repo;
    private final NamedDatasetQueryRepository links;
    private final QueryRepository queryRepo;
    private final NamedDatasetMapper mapper;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    /**
     * Creates the service.
     *
     * @param repo the named datasets
     * @param links the join rows between named datasets and queries
     * @param queryRepo the query rows
     * @param mapper the mapper to the public shapes
     * @param transactionManager the transaction manager each call opens its transaction on
     */
    public NamedDatasetService(
        NamedDatasetRepository repo, NamedDatasetQueryRepository links, QueryRepository queryRepo, NamedDatasetMapper mapper,
        PlatformTransactionManager transactionManager
    ) {
        this.repo = repo;
        this.links = links;
        this.queryRepo = queryRepo;
        this.mapper = mapper;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
    }

    /**
     * Lists the caller's named datasets, each with its linked queries.
     *
     * @param user the caller
     * @return the caller's datasets, archived ones included
     * @throws PicsureException 401 when the caller has no email
     */
    public List<NamedDatasetDto> listForUser(GatewayUser user) {
        String email = requireEmail(user);
        return afterStoringDerivedResultTypes(reads.execute(status -> view(repo.findByUser(email))));
    }

    /**
     * Reads one of the caller's named datasets with its linked queries.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @return the dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id
     */
    public NamedDatasetDto getForUser(GatewayUser user, UUID id) {
        String email = requireEmail(user);
        View view = reads.execute(status -> view(List.of(repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id)))));
        return afterStoringDerivedResultTypes(view).getFirst();
    }

    /**
     * Names a query as a dataset of the caller. The dataset's {@code queryId} is set to the query, and a join row links the same query with
     * the variant its body names.
     *
     * @param user the caller
     * @param req the query to name and the name, archived state and metadata to store
     * @return the created dataset
     * @throws PicsureException 401 when the caller has no email, 404 when no query has the id, 409 when the caller already named the query
     */
    public NamedDatasetDto create(GatewayUser user, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        View view = writes.execute(status -> {
            Query query = resolveQuery(req.queryId());
            NamedDataset saved = saveOrConflict(mapper.toEntity(email, query, req), req.queryId(), email);
            NamedDatasetQuery link = insertOrConflict(saved, query, mapper.variantOf(query));
            return view(List.of(saved), List.of(link));
        });
        return afterStoringDerivedResultTypes(view).getFirst();
    }

    /**
     * Updates the name, archived state and metadata of one of the caller's datasets, and links the query the request names when the dataset
     * does not link it yet.
     *
     * <p>A query is linked already when the dataset's {@code queryId} names it or a join row holds it. Any other query is linked by a new
     * join row, under three rules. The query row must belong to the caller, by the gateway user id, or have no owner. The dataset must not
     * hold a query of the same variant yet. The dataset's {@code queryId} is set to the query only when the dataset has none and the query
     * is not a sample, and is left alone otherwise.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @param req the query to link and the name, archived state and metadata to store
     * @return the updated dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id or no query has the id to
     *         link, 403 when the query to link belongs to another user, 409 when the dataset already holds a query of the same variant or
     *         the caller already named the query the {@code queryId} would move to
     */
    public NamedDatasetDto update(GatewayUser user, UUID id, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        View view = writes.execute(status -> {
            NamedDataset existing = repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id));
            List<NamedDatasetQuery> rows = new ArrayList<>(links.findByDatasets(List.of(id)));
            Query toLink = isLinked(existing, rows, req.queryId()) ? null : resolveQuery(req.queryId());
            QueryVariant variant = toLink == null ? null : linkableVariantOf(user, existing, rows, toLink);
            if (toLink != null && existing.getQuery() == null && !variant.sample()) {
                existing.setQuery(toLink);
            }
            existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
            NamedDataset saved = saveOrConflict(existing, req.queryId(), email);
            if (toLink != null) {
                rows.add(insertOrConflict(saved, toLink, variant));
            }
            return view(List.of(saved), rows);
        });
        return afterStoringDerivedResultTypes(view).getFirst();
    }

    /**
     * Deletes one of the caller's named datasets. Its join rows go with it, removed by the database through the foreign key of
     * {@code named_dataset_query}. The queries it linked stay.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id
     */
    public void delete(GatewayUser user, UUID id) {
        String email = requireEmail(user);
        writes.executeWithoutResult(status -> repo.delete(repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id))));
    }

    /**
     * The datasets of one answer, and the result types that were derived for join rows that store none.
     *
     * @param datasets the datasets in their public shape
     * @param derived the result types to store, each for one join row
     */
    private record View(List<NamedDatasetDto> datasets, List<DerivedResultType> derived) {
    }

    /**
     * A result type read from a query body for a join row that stores none.
     *
     * @param dataset the id of the named dataset of the join row
     * @param query the id of the query of the join row
     * @param resultType the result type the body names
     */
    private record DerivedResultType(UUID dataset, UUID query, String resultType) {
    }

    private View view(List<NamedDataset> datasets) {
        if (datasets.isEmpty()) {
            return new View(List.of(), List.of());
        }
        return view(datasets, links.findByDatasets(datasets.stream().map(NamedDataset::getUuid).toList()));
    }

    /**
     * Builds the public shape of each dataset with its linked queries: the query its {@code queryId} names when no join row holds that
     * query, then its join rows in the order given. A join row that stores no result type gets the one its query body names.
     *
     * @param datasets the datasets to show
     * @param rows the join rows of all of them, in the order they are to be returned
     * @return the datasets and the result types that were derived
     */
    private View view(List<NamedDataset> datasets, List<NamedDatasetQuery> rows) {
        Map<UUID, List<NamedDatasetQuery>> rowsByDataset =
            rows.stream().collect(Collectors.groupingBy(NamedDatasetQuery::getNamedDatasetUuid));
        Map<UUID, Query> bodies = bodiesOfUntypedRows(datasets, rows);
        List<DerivedResultType> derived = new ArrayList<>();
        List<NamedDatasetDto> shown = new ArrayList<>();
        for (NamedDataset dataset : datasets) {
            List<NamedDatasetQuery> datasetRows = rowsByDataset.getOrDefault(dataset.getUuid(), List.of());
            List<LinkedQueryDto> queries = new ArrayList<>();
            Query named = dataset.getQuery();
            if (named != null && datasetRows.stream().noneMatch(row -> row.getQueryUuid().equals(named.getUuid()))) {
                QueryVariant variant = mapper.variantOf(named);
                queries.add(new LinkedQueryDto(named.getUuid(), variant.sample(), variant.resultType()));
            }
            for (NamedDatasetQuery row : datasetRows) {
                String resultType = row.getResultType();
                if (resultType == null) {
                    resultType = resultTypeOf(bodies.get(row.getQueryUuid()));
                    if (resultType != null) {
                        derived.add(new DerivedResultType(row.getNamedDatasetUuid(), row.getQueryUuid(), resultType));
                    }
                }
                queries.add(new LinkedQueryDto(row.getQueryUuid(), row.isSample(), resultType));
            }
            shown.add(mapper.toDto(dataset, queries));
        }
        return new View(shown, derived);
    }

    /**
     * Returns the query rows of the join rows that store no result type, by query id. The query a dataset's {@code queryId} names is
     * already loaded with the dataset, and only the others are fetched.
     */
    private Map<UUID, Query> bodiesOfUntypedRows(List<NamedDataset> datasets, List<NamedDatasetQuery> rows) {
        Map<UUID, Query> bodies = new HashMap<>();
        for (NamedDataset dataset : datasets) {
            if (dataset.getQuery() != null) {
                bodies.put(dataset.getQuery().getUuid(), dataset.getQuery());
            }
        }
        Set<UUID> missing = rows.stream().filter(row -> row.getResultType() == null).map(NamedDatasetQuery::getQueryUuid)
            .filter(query -> !bodies.containsKey(query)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missing.isEmpty()) {
            queryRepo.findAllById(missing).forEach(query -> bodies.put(query.getUuid(), query));
        }
        return bodies;
    }

    private String resultTypeOf(Query query) {
        return query == null ? null : mapper.variantOf(query).resultType();
    }

    /**
     * Stores each derived result type on its join row, after the transaction that derived it has ended. A row another request filled in the
     * meantime is left alone. When the variant key refuses the value, because another row of the dataset already has the same variant, the
     * row keeps its null result type and the answer is returned all the same.
     */
    private List<NamedDatasetDto> afterStoringDerivedResultTypes(View view) {
        for (DerivedResultType derived : view.derived()) {
            try {
                links.fillResultType(derived.dataset(), derived.query(), derived.resultType());
            } catch (DataIntegrityViolationException sameVariantAlreadyStored) {
                LOG.warn(
                    "Named dataset {} already links a query with result type {}; the join row for query {} keeps no result type",
                    derived.dataset(), derived.resultType(), derived.query()
                );
            }
        }
        return view.datasets();
    }

    private static boolean isLinked(NamedDataset dataset, List<NamedDatasetQuery> rows, UUID queryId) {
        Query named = dataset.getQuery();
        return (named != null && named.getUuid().equals(queryId)) || rows.stream().anyMatch(row -> row.getQueryUuid().equals(queryId));
    }

    /**
     * Returns the variant of a query the caller may link to the dataset.
     *
     * @throws PicsureException 403 when the query belongs to another user, 409 when the dataset already holds a query of its variant
     */
    private QueryVariant linkableVariantOf(GatewayUser user, NamedDataset dataset, List<NamedDatasetQuery> rows, Query query) {
        requireOwnedByCallerOrOwnerless(user, query);
        QueryVariant variant = mapper.variantOf(query);
        if (variantsOf(dataset, rows).contains(variant)) {
            throw PicsureExceptions.conflict(
                "NamedDataset " + dataset.getUuid() + " already links a query with sample " + variant.sample() + " and result type "
                    + variant.resultType()
            );
        }
        return variant;
    }

    /**
     * The owner of a query row is a user UUID, and the caller is the gateway user id. The two match when the id is that UUID in its
     * canonical text form, in either letter case. A row with no owner can be linked by anyone, because rows stored before queries had
     * owners have none.
     */
    private static void requireOwnedByCallerOrOwnerless(GatewayUser user, Query query) {
        UUID owner = query.getUserUuid();
        if (owner != null && !owner.toString().equalsIgnoreCase(user.getUserId())) {
            throw PicsureExceptions.forbidden("Query " + query.getUuid() + " belongs to another user");
        }
    }

    /**
     * The variants a dataset already holds: the one of each join row, with a missing result type read from the query body, and the one of
     * the query its {@code queryId} names when no join row holds that query.
     */
    private Set<QueryVariant> variantsOf(NamedDataset dataset, List<NamedDatasetQuery> rows) {
        Map<UUID, Query> bodies = bodiesOfUntypedRows(List.of(dataset), rows);
        Set<QueryVariant> variants = new LinkedHashSet<>();
        Query named = dataset.getQuery();
        if (named != null && rows.stream().noneMatch(row -> row.getQueryUuid().equals(named.getUuid()))) {
            variants.add(mapper.variantOf(named));
        }
        for (NamedDatasetQuery row : rows) {
            String resultType = row.getResultType() != null ? row.getResultType() : resultTypeOf(bodies.get(row.getQueryUuid()));
            variants.add(new QueryVariant(row.isSample(), resultType));
        }
        return variants;
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

    /**
     * {@code saveAndFlush} (not {@code save}) so the {@code unique_queryId_user} constraint violation -- if any -- is raised by the
     * database and thrown here, inside the try/catch, rather than deferred to end-of-transaction flush where it could no longer be
     * translated into a 409.
     */
    private NamedDataset saveOrConflict(NamedDataset entity, UUID queryId, String user) {
        try {
            return repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw PicsureExceptions.conflict("A NamedDataset for query " + queryId + " and user '" + user + "' already exists");
        }
    }

    /**
     * Inserts the join row that links a query to a dataset. The insert is flushed here so that a key the database refuses, which a second
     * request linking the same variant at the same moment runs into, is answered with 409.
     */
    private NamedDatasetQuery insertOrConflict(NamedDataset dataset, Query query, QueryVariant variant) {
        NamedDatasetQuery link = new NamedDatasetQuery(
            dataset.getUuid(), query.getUuid(), variant.sample(), variant.resultType(), Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
        try {
            links.saveAndFlush(link);
            return link;
        } catch (DataIntegrityViolationException e) {
            throw PicsureExceptions.conflict(
                "NamedDataset " + dataset.getUuid() + " already links query " + query.getUuid() + " or another query of its variant"
            );
        }
    }

    private static PicsureException notFound(UUID id) {
        return PicsureExceptions.notFound("NamedDataset", id);
    }
}
