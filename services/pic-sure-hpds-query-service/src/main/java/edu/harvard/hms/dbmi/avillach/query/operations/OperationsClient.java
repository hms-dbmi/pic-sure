package edu.harvard.hms.dbmi.avillach.query.operations;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * The DB-free persistence path: this service owns no database, so every read/write of a stored query goes over HTTP to
 * pic-sure-operations-service's internal query API. The wrapped {@link RestClient} is pre-configured (base URL + the
 * {@code X-PIC-SURE-INTERNAL-TOKEN} shared secret, see {@code OperationsClientConfig}) so every call made through this class automatically
 * authenticates to operations-service's {@code InternalTokenFilter}.
 *
 * <p>Method/endpoint contracts are FIXED by operations-service (see
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.InternalQueryController}); the DTOs here are client-side copies of that service's
 * request/response shapes, matched byte-for-byte since this module cannot depend on operations-service's classes without pulling in its
 * JPA/entity graph.
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

    private static final String QUERIES_PATH = "/operations/internal/queries";

    private final RestClient http;

    public OperationsClient(RestClient http) {
        this.http = http;
    }

    /** {@code POST /operations/internal/queries} -&gt; 201 {@code { "picsureId": "<uuid>" } }. */
    public UUID save(SaveQueryRequest request) {
        SaveQueryResponse response =
            execute(() -> http.post().uri(QUERIES_PATH).body(request).retrieve().body(SaveQueryResponse.class), "save");
        if (response == null || response.picsureId() == null) {
            throw badGateway("save", "operations-service returned no picsureId");
        }
        return response.picsureId();
    }

    /** {@code GET /operations/internal/queries/{id}} -&gt; 200 {@link StoredQuery}; 404 -&gt; {@link PicsureException} NOT_FOUND. */
    public StoredQuery get(UUID picsureId) {
        try {
            return http.get().uri(QUERIES_PATH + "/{id}", picsureId).retrieve().body(StoredQuery.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new PicsureException(HttpStatus.NOT_FOUND, "not_found", "No stored query for picsureId " + picsureId);
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

    /** Shape of the {@code POST /operations/internal/queries} response body: {@code { "picsureId": "<uuid>" } }. */
    private record SaveQueryResponse(UUID picsureId) {
    }
}
