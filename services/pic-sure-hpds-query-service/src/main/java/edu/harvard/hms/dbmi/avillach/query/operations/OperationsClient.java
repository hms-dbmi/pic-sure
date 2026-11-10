package edu.harvard.hms.dbmi.avillach.query.operations;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SavedQueryReference;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
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
 * below, since no NOT_FOUND semantics are specified for that call yet.
 *
 * <p>NOT a {@code @Component}: it is registered exactly once, by {@code OperationsClientConfig}, wired to the specific
 * {@code operationsRestClient} bean -- the context also contains a second, differently-configured {@code RestClient} bean for HPDS (see
 * {@code HpdsClientConfig}), so relying on by-type autowiring here would be ambiguous.
 */
public class OperationsClient {

    private static final Logger logger = LoggerFactory.getLogger(OperationsClient.class);
    private static final String QUERIES_PATH = "/operations/internal/queries";

    private final RestClient http;

    public OperationsClient(RestClient http) {
        this.http = http;
    }

    /**
     * {@code POST /operations/internal/queries} -&gt; 201 {@code { "picsureId": "<uuid>" } }.
     *
     * <p>A 409 means operations-service holds the row under an owner other than the one the request names. The id of an owned row is
     * derived from its owner, so that can only follow from a fault on the server side. It is reported as a bad gateway whose message
     * repeats nothing operations-service said.
     *
     * @param request the query to store
     * @return the id the row is stored under
     * @throws PicsureException BAD_GATEWAY when operations-service refuses the save, fails, or answers without an id; GATEWAY_TIMEOUT when
     *         it cannot be reached in time
     */
    public UUID save(SaveQueryRequest request) {
        SavedQueryReference response = execute(() -> {
            try {
                return http.post().uri(QUERIES_PATH).body(request).retrieve().body(SavedQueryReference.class);
            } catch (HttpClientErrorException.Conflict refused) {
                logger.error(
                    "operations-service refused to store query {} because the stored row has a different owner", request.picsureId()
                );
                throw badGateway("save", "the query could not be stored");
            }
        }, "save");
        if (response == null || response.picsureId() == null) {
            throw badGateway("save", "operations-service returned no picsureId");
        }
        return response.picsureId();
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

    /** {@code PATCH /operations/internal/queries/{id}}; null fields on {@code request} mean "leave unchanged". */
    public void update(UUID picsureId, UpdateQueryRequest request) {
        execute(() -> {
            http.patch().uri(QUERIES_PATH + "/{id}", picsureId).body(request).retrieve().toBodilessEntity();
            return null;
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
