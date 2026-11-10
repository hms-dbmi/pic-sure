package edu.harvard.hms.dbmi.avillach.query.consent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

@Service
public class ConsentAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(ConsentAuthorizationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PsamaConsentClient client;
    private final ConsentFilterBuilder filterBuilder;
    private final boolean enabled;

    public ConsentAuthorizationService(
        PsamaConsentClient client, ConsentFilterBuilder filterBuilder, @Value("${consent.based.authorization.enabled:true}") boolean enabled
    ) {
        this.client = client;
        this.filterBuilder = filterBuilder;
        this.enabled = enabled;
        logger.info("Consent-based authorization enabled: {}", enabled);
    }

    public void scopeQuery(String backend, QueryRequest request, String authorizationHeader) {
        if (!enabled || !"auth".equals(backend)) {
            return;
        }
        requireAuthorizationHeader(authorizationHeader);
        request.setQuery(filterBuilder.apply(typed(request), client.fetch(authorizationHeader)));
    }

    /**
     * Returns the consents the caller holds right now, in the form a query row stores them.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param authorizationHeader the caller's {@code Authorization} header
     * @return the consents as consent concept path to consent values, ordered by path with each value list sorted; empty, and read from
     *         nowhere, when consent authorization is off or the backend is not {@code auth}
     * @throws PicsureException 403 {@code consent_denied} when the caller holds no consents; 502 {@code consent_lookup_failed} when the
     *         header is missing or the lookup fails
     */
    public Map<String, List<String>> currentConsents(String backend, String authorizationHeader) {
        if (!enabled || !"auth".equals(backend)) {
            return Map.of();
        }
        requireAuthorizationHeader(authorizationHeader);
        Map<String, Set<String>> held = client.fetch(authorizationHeader);
        if (held == null || held.isEmpty()) {
            throw new PicsureException(HttpStatus.FORBIDDEN, "consent_denied", "Consent does not permit this request");
        }
        Map<String, List<String>> sorted = new TreeMap<>();
        held.forEach((conceptPath, values) -> sorted.put(conceptPath, values == null ? List.of() : values.stream().sorted().toList()));
        return sorted;
    }

    /**
     * Scopes the query of a request exactly as {@link #scopeQuery} does, with consents the caller of this method already fetched through
     * {@link #currentConsents}. A route that also stores the consents uses it so that the filters it sends and the consents it stores come
     * from one lookup.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param request the envelope whose query is replaced by the scoped one; left as it is when consent authorization is off or the backend
     *        is not {@code auth}
     * @param consents the caller's consents as {@link #currentConsents} returned them
     * @throws PicsureException 403 {@code consent_denied} when the consents do not cover a filter the query needs
     */
    public void scopeWithConsents(String backend, QueryRequest request, Map<String, List<String>> consents) {
        if (!enabled || !"auth".equals(backend)) {
            return;
        }
        Map<String, Set<String>> held = new HashMap<>();
        consents.forEach((conceptPath, values) -> held.put(conceptPath, new HashSet<>(values)));
        request.setQuery(filterBuilder.apply(typed(request), held));
    }

    private static Query typed(QueryRequest request) {
        return request.getQuery() instanceof Query typed ? typed : MAPPER.convertValue(request.getQuery(), Query.class);
    }

    public void verifyReadAccess(String backend, StoredQuery stored, String authorizationHeader) {
        if (!enabled || !"auth".equals(backend)) {
            return;
        }
        requireAuthorizationHeader(authorizationHeader);
        List<AuthorizationFilter> savedFilters = savedFilters(stored);
        Map<String, Set<String>> currentConsents = client.fetch(authorizationHeader);
        boolean stillAuthorized = savedFilters.stream().allMatch(saved -> {
            Set<String> currentValues = currentConsents.get(saved.conceptPath());
            return saved.values() != null && !saved.values().isEmpty() && currentValues != null
                && currentValues.containsAll(saved.values());
        });
        if (!stillAuthorized) {
            throw consentDenied();
        }
    }

    private static List<AuthorizationFilter> savedFilters(StoredQuery stored) {
        try {
            JsonNode filters = MAPPER.readTree(stored.query()).path("query").path("authorizationFilters");
            if (!filters.isArray() || filters.isEmpty()) {
                throw consentDenied();
            }
            return MAPPER.convertValue(filters, new TypeReference<>() {});
        } catch (PicsureException error) {
            throw error;
        } catch (Exception error) {
            throw consentDenied();
        }
    }

    private static void requireAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new PicsureException(HttpStatus.BAD_GATEWAY, "consent_lookup_failed", "Unable to verify the caller's consents");
        }
    }

    private static PicsureException consentDenied() {
        return new PicsureException(HttpStatus.FORBIDDEN, "consent_denied", "You no longer have consent for this saved result");
    }
}
