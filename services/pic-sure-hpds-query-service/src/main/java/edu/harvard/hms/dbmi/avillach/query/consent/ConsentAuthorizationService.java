package edu.harvard.hms.dbmi.avillach.query.consent;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;

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
        Query query = request.getQuery() instanceof Query typed ? typed : MAPPER.convertValue(request.getQuery(), Query.class);
        request.setQuery(filterBuilder.apply(query, client.fetch(authorizationHeader)));
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
