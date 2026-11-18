package edu.harvard.hms.dbmi.avillach.query.operations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SavedQueryReference;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.StoredQueryVariant;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * The DB-free persistence path: this service owns no database, so every read/write of a stored query goes over HTTP to
 * pic-sure-operations-service's internal query API. The wrapped {@link RestClient} is pre-configured (base URL + the
 * {@code X-PIC-SURE-INTERNAL-TOKEN} shared secret, see {@code OperationsClientConfig}) so every call made through this class automatically
 * authenticates to operations-service's {@code InternalTokenFilter}.
 *
 * <p>Method/endpoint contracts are FIXED by operations-service (see
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.InternalQueryController}). The request and response records are the shared ones in
 * {@code edu.harvard.dbmi.avillach.domain}, which operations-service binds as well, so the two sides read and write one definition.
 *
 * <p>A persistence failure must never be swallowed: any 5xx, timeout, or malformed response from operations-service is surfaced as a
 * {@link PicsureException} (BAD_GATEWAY or GATEWAY_TIMEOUT) rather than returning a null/partial result. Only {@link #get(UUID)} has a
 * documented 404 contract (NOT_FOUND); a 404 from {@link #update(UUID, UpdateQueryRequest)} falls into the generic bad-gateway handling
 * below, since no NOT_FOUND semantics are specified for that call yet. A 409 from an update is a failure on
 * {@link #update(UUID, UpdateQueryRequest)} and an expected answer on {@link #updateLatestRun(UUID, UpdateQueryRequest)}.
 *
 * <p>NOT a {@code @Component}: it is registered exactly once, by {@code OperationsClientConfig}, wired to the specific
 * {@code operationsRestClient} bean -- the context also contains a second, differently-configured {@code RestClient} bean for HPDS (see
 * {@code HpdsClientConfig}), so relying on by-type autowiring here would be ambiguous.
 */
public class OperationsClient {

    private static final Logger logger = LoggerFactory.getLogger(OperationsClient.class);
    private static final String QUERIES_PATH = "/operations/internal/queries";
    private static final ParameterizedTypeReference<List<StoredQueryVariant>> VARIANT_LIST = new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public OperationsClient(RestClient http) {
        this.http = http;
    }

    /**
     * {@code POST /operations/internal/queries} -&gt; 201 {@code { "picsureId": "<uuid>" } }.
     *
     * <p>A 409 means operations-service holds the row under an owner other than the one the request names. The id of an owned row is
     * derived from its owner, so that can only follow from a fault on the server side. It is reported as
     * {@link #storedUnderAnotherOwner()}, whose message repeats nothing operations-service said.
     *
     * @param request the query to store
     * @return the id the row is stored under
     * @throws PicsureException INTERNAL_SERVER_ERROR when operations-service holds the row under another owner; BAD_GATEWAY when it refuses
     *         the save for any other reason, fails, or answers without an id; GATEWAY_TIMEOUT when it cannot be reached in time
     */
    public UUID save(SaveQueryRequest request) {
        SavedQueryReference response = execute(() -> {
            try {
                return http.post().uri(QUERIES_PATH).body(request).retrieve().body(SavedQueryReference.class);
            } catch (HttpClientErrorException.Conflict refused) {
                logger.error(
                    "operations-service refused to store query {} because the stored row has a different owner", request.picsureId()
                );
                throw storedUnderAnotherOwner();
            }
        }, "save");
        if (response == null || response.picsureId() == null) {
            throw badGateway("save", "operations-service returned no picsureId");
        }
        return response.picsureId();
    }

    /**
     * The fault for a row found under an id derived for one owner and stored under another owner, or under none. Whoever detects it, this
     * client on a save or its caller on a read, the client receives the same answer.
     *
     * @return a 500 {@code internal_server_error} whose message names no owner and no id
     */
    public static PicsureException storedUnderAnotherOwner() {
        return new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "The query could not be stored");
    }

    /** {@code GET /operations/internal/queries/{id}} -&gt; 200 {@link StoredQuery}; 404 -&gt; {@link PicsureException} NOT_FOUND. */
    public StoredQuery get(UUID picsureId) {
        return find(picsureId)
            .orElseThrow(() -> new PicsureException(HttpStatus.NOT_FOUND, "not_found", "No stored query for picsureId " + picsureId));
    }

    /**
     * {@code GET /operations/internal/queries/{id}} for a caller that expects the row may not exist yet.
     *
     * @param picsureId the id of the row
     * @return the stored row, or empty when operations-service answers 404
     * @throws PicsureException BAD_GATEWAY when operations-service fails; GATEWAY_TIMEOUT when it cannot be reached in time
     */
    public Optional<StoredQuery> find(UUID picsureId) {
        try {
            return Optional.ofNullable(http.get().uri(QUERIES_PATH + "/{id}", picsureId).retrieve().body(StoredQuery.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (ResourceAccessException e) {
            throw gatewayTimeout("get", e);
        } catch (RestClientException e) {
            throw badGateway("get", e.getMessage());
        }
    }

    /**
     * {@code GET /operations/internal/queries/variants?queryToVariantUuid={id}&userUuid={id}} -&gt; 200 with the rows one user stored under
     * one general query, oldest first, rows with no creation time last.
     *
     * <p>An empty list is an answer, not a failure: operations-service gives it for a user with no row under the general query, for a
     * general query of another user and for one that does not exist.
     *
     * @param queryToVariantUuid the id of the general query
     * @param userUuid the UUID of the user whose rows are listed
     * @return the variants in the order operations-service lists them, or an empty list
     * @throws PicsureException BAD_GATEWAY when operations-service refuses the request, fails, or answers without a list; GATEWAY_TIMEOUT
     *         when it cannot be reached in time
     */
    public List<StoredQueryVariant> variantsOf(UUID queryToVariantUuid, UUID userUuid) {
        List<StoredQueryVariant> variants = execute(
            () -> http.get().uri(QUERIES_PATH + "/variants?queryToVariantUuid={general}&userUuid={owner}", queryToVariantUuid, userUuid)
                .retrieve().body(VARIANT_LIST),
            "variants"
        );
        if (variants == null) {
            throw badGateway("variants", "operations-service returned no list");
        }
        return variants;
    }

    /** {@code PATCH /operations/internal/queries/{id}}; null fields on {@code request} mean "leave unchanged". */
    public void update(UUID picsureId, UpdateQueryRequest request) {
        execute(() -> {
            http.patch().uri(QUERIES_PATH + "/{id}", picsureId).body(request).retrieve().toBodilessEntity();
            return null;
        }, "update");
    }

    /**
     * {@code PATCH /operations/internal/queries/{id}} for a change that belongs to one run of the row, such as the status a poller read for
     * it. The request names the run the caller loaded with the row, and operations-service applies the change only while that run is still
     * the latest run of the row.
     *
     * <p>A 409 means the row has been run again since the caller read it. That is not a failure of the caller's request: the newer run owns
     * the row now and nothing was changed.
     *
     * @param picsureId the id of the row
     * @param request the fields to change, with the id of the run they belong to
     * @return true when operations-service applied the change, false when it answered 409 and changed nothing
     * @throws PicsureException BAD_GATEWAY when operations-service refuses the update for any other reason or fails; GATEWAY_TIMEOUT when
     *         it cannot be reached in time
     */
    public boolean updateLatestRun(UUID picsureId, UpdateQueryRequest request) {
        return execute(() -> {
            try {
                http.patch().uri(QUERIES_PATH + "/{id}", picsureId).body(request).retrieve().toBodilessEntity();
                return true;
            } catch (HttpClientErrorException.Conflict rowHasANewerRun) {
                return false;
            }
        }, "update");
    }

    private <T> T execute(java.util.function.Supplier<T> call, String operation) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw gatewayTimeout(operation, e);
        } catch (RestClientException e) {
            throw badGateway(operation, e.getMessage());
        }
    }

    private PicsureException badGateway(String operation, String detail) {
        return new PicsureException(HttpStatus.BAD_GATEWAY, "bad_gateway", "operations-service " + operation + " failed: " + detail);
    }

    private PicsureException gatewayTimeout(String operation, Exception cause) {
        return new PicsureException(
            HttpStatus.GATEWAY_TIMEOUT, "gateway_timeout", "operations-service " + operation + " timed out: " + cause.getMessage()
        );
    }
}
