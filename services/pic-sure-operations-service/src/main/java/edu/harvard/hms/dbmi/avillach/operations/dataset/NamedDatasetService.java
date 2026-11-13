package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>{@code NamedDataset} carries a DB-level unique constraint on {@code (queryId, user)}. Rather than a check-then-save pre-check (which
 * would race under concurrent requests), {@link #create} and {@link #update} (when it repoints the query) use {@code saveAndFlush} inside a
 * try/catch so the constraint violation surfaces synchronously and is translated to {@code 409 CONFLICT} via {@link PicsureException},
 * mirroring how {@code ConfigurationService} handles its name+kind conflict.
 *
 * <p>A dataset points at one query row through {@code queryId}. {@link #create} sets it, and {@link #update} moves it when the request
 * names another query. A dataset is pointed only at a query row that belongs to the caller or has no owner. That check is made when
 * {@code queryId} is written and at no other time: a read, and an update that names the query the dataset already points at, do not look at
 * who owns that row.
 *
 * <p>A dataset also names the general query of its query row, in {@code queryToVariantUuid}. A general query is a user's query whatever its
 * result type and whether or not it is a sample, and every query row that has an owner is one variant of one. {@link #create} and
 * {@link #update} copy the id from the query row each time, so a dataset stored without it takes it on its next update. A dataset on a
 * query row that has no owner has no general query.
 *
 * <p>Every answer lists the dataset's {@code queries}: the query rows filed under its general query that belong to the caller, by the
 * gateway user id. They are found by the general query alone. Nothing links a query to a dataset, no stored query body is read to list
 * them, and a read writes nothing.
 */
@Service
public class NamedDatasetService {

    /** The canonical text form of a UUID: 36 characters, hexadecimal digits in either case, hyphens after digits 8, 12, 16 and 20. */
    private static final Pattern CANONICAL_UUID =
        Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    private final NamedDatasetRepository repo;
    private final QueryRepository queryRepo;
    private final NamedDatasetMapper mapper;

    /**
     * Creates the service.
     *
     * @param repo the named datasets
     * @param queryRepo the query rows
     * @param mapper the mapper to the public shapes
     */
    public NamedDatasetService(NamedDatasetRepository repo, QueryRepository queryRepo, NamedDatasetMapper mapper) {
        this.repo = repo;
        this.queryRepo = queryRepo;
        this.mapper = mapper;
    }

    /**
     * Lists the caller's named datasets, each with the variants of its general query that belong to the caller.
     *
     * @param user the caller
     * @return the caller's datasets, archived ones included
     * @throws PicsureException 401 when the caller has no email
     */
    @Transactional(readOnly = true)
    public List<NamedDatasetDto> listForUser(GatewayUser user) {
        return withVariants(user, repo.findByUser(requireEmail(user)));
    }

    /**
     * Reads one of the caller's named datasets with the variants of its general query that belong to the caller.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @return the dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id
     */
    @Transactional(readOnly = true)
    public NamedDatasetDto getForUser(GatewayUser user, UUID id) {
        NamedDataset dataset = repo.findByUuidAndUser(id, requireEmail(user)).orElseThrow(() -> notFound(id));
        return withVariants(user, List.of(dataset)).getFirst();
    }

    /**
     * Names a query as a dataset of the caller. The dataset's {@code queryId} is set to the query, and the dataset is filed under the
     * general query of that query row. The query row must belong to the caller, by the gateway user id, or have no owner. That is checked
     * before anything is saved, so a refused request stores nothing.
     *
     * @param user the caller
     * @param req the query to name and the name, archived state and metadata to store
     * @return the created dataset
     * @throws PicsureException 401 when the caller has no email, 404 when no query has the id, 403 when the query belongs to another user,
     *         409 when the caller already named the query
     */
    @Transactional
    public NamedDatasetDto create(GatewayUser user, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        Query query = resolveQuery(req.queryId());
        requireOwnedByCallerOrOwnerless(user, query);
        NamedDataset created = underTheGeneralQueryOfItsRow(mapper.toEntity(email, query, req));
        return withVariants(user, List.of(saveOrConflict(created, req.queryId(), email))).getFirst();
    }

    /**
     * Updates the name, archived state and metadata of one of the caller's datasets, and points the dataset at the query the request names
     * when that is not the query it points at already.
     *
     * <p>The query to move to must belong to the caller, by the gateway user id, or have no owner. A request that names another user's
     * query is refused before anything is changed. A request that names the query the dataset already points at moves nothing and makes no
     * owner check. Either way the dataset is filed under the general query of the query row it ends up on, so moving it onto a query row
     * that has no owner leaves it with no general query.
     *
     * @param user the caller
     * @param id the id of the dataset
     * @param req the query to point at and the name, archived state and metadata to store
     * @return the updated dataset
     * @throws PicsureException 401 when the caller has no email, 404 when the caller has no dataset with the id or no query has the id to
     *         move to, 403 when the query to move to belongs to another user, 409 when the caller already named the query to move to
     */
    @Transactional
    public NamedDatasetDto update(GatewayUser user, UUID id, NamedDatasetRequestDto req) {
        String email = requireEmail(user);
        NamedDataset existing = repo.findByUuidAndUser(id, email).orElseThrow(() -> notFound(id));
        if (existing.getQuery() == null || !existing.getQuery().getUuid().equals(req.queryId())) {
            Query query = resolveQuery(req.queryId());
            requireOwnedByCallerOrOwnerless(user, query);
            existing.setQuery(query);
        }
        underTheGeneralQueryOfItsRow(existing);
        existing.setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
        return withVariants(user, List.of(saveOrConflict(existing, req.queryId(), email))).getFirst();
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
     * Builds the public shape of each dataset with its {@code queries}: the caller's query rows under the dataset's general query, in the
     * order {@link QueryRepository#findVariantsOf} returns them. One query fetches the rows of all the datasets. A dataset with no general
     * query lists none, and so does every dataset when the caller's gateway user id is not a UUID.
     *
     * @param user the caller
     * @param datasets the datasets to show
     * @return the datasets in the order given
     */
    private List<NamedDatasetDto> withVariants(GatewayUser user, List<NamedDataset> datasets) {
        Map<UUID, List<LinkedQueryDto>> variants = callersVariantsByGeneralQuery(user, datasets);
        return datasets.stream().map(dataset -> {
            UUID generalQuery = dataset.getQueryToVariantUuid();
            return mapper.toDto(dataset, generalQuery == null ? List.of() : variants.getOrDefault(generalQuery, List.of()));
        }).toList();
    }

    private Map<UUID, List<LinkedQueryDto>> callersVariantsByGeneralQuery(GatewayUser user, List<NamedDataset> datasets) {
        Optional<UUID> caller = uuidOf(user);
        Set<UUID> generalQueries = datasets.stream().map(NamedDataset::getQueryToVariantUuid).filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
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
     * Files a dataset under the general query of the query row it points at, or under none when it points at no row or that row has no
     * general query.
     *
     * @param dataset the dataset, pointing at the query row it is to keep
     * @return the same dataset
     */
    private static NamedDataset underTheGeneralQueryOfItsRow(NamedDataset dataset) {
        Query row = dataset.getQuery();
        return dataset.setQueryToVariantUuid(row == null ? null : row.getQueryToVariantUuid());
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

    private static PicsureException notFound(UUID id) {
        return PicsureExceptions.notFound("NamedDataset", id);
    }
}
