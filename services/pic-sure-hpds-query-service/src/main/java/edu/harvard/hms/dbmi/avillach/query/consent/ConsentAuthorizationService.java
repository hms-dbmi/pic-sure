package edu.harvard.hms.dbmi.avillach.query.consent;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.UserConsent;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;

/**
 * Scopes auth-backend queries to the caller's consents. The caller's own {@code userConsents} are never trusted: whatever the body carries
 * is replaced with the set PSAMA reports for the bearer token, which HPDS then uses to pick the phenotypic partitions it reads (see
 * {@code PartitionedPhenotypicObservationStore}). The deprecated {@code authorizationFilters} are left untouched — they no longer carry
 * authorization, so a client-supplied one can only narrow its own result.
 */
@Service
public class ConsentAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(ConsentAuthorizationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PsamaConsentClient client;
    private final boolean enabled;

    public ConsentAuthorizationService(PsamaConsentClient client, @Value("${consent.based.authorization.enabled:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
        logger.info("Consent-based authorization enabled: {}", enabled);
    }

    public void scopeQuery(String backend, QueryRequest request, String authorizationHeader) {
        if (!enabled || !"auth".equals(backend)) {
            return;
        }
        requireAuthorizationHeader(authorizationHeader);
        Query query = request.getQuery() instanceof Query typed ? typed : MAPPER.convertValue(request.getQuery(), Query.class);
        request.setQuery(query.setUserConsents(asUserConsents(client.fetch(authorizationHeader))));
    }

    public void verifyReadAccess(String backend, StoredQuery stored, String authorizationHeader) {
        if (!enabled || !"auth".equals(backend)) {
            return;
        }
        requireAuthorizationHeader(authorizationHeader);
        Set<String> savedConsents = savedConsents(stored);
        if (!client.fetch(authorizationHeader).containsAll(savedConsents)) {
            throw consentDenied();
        }
    }

    private static Set<UserConsent> asUserConsents(Set<String> consents) {
        Set<UserConsent> userConsents = consents.stream().filter(Objects::nonNull).filter(consent -> !consent.isBlank())
            .map(UserConsent::new).collect(Collectors.toSet());
        if (userConsents.isEmpty()) {
            throw consentDenied();
        }
        return userConsents;
    }

    /**
     * The consents the query was scoped to when it was stored. A saved query with none of them predates consent scoping or was written by
     * something that bypassed it, so it is not readable rather than readable by anyone.
     */
    private static Set<String> savedConsents(StoredQuery stored) {
        try {
            JsonNode consents = MAPPER.readTree(stored.query()).path("query").path("userConsents");
            if (!consents.isArray() || consents.isEmpty()) {
                throw consentDenied();
            }
            Set<String> values = MAPPER.convertValue(consents, new TypeReference<Set<UserConsent>>() {}).stream().map(UserConsent::value)
                .filter(Objects::nonNull).collect(Collectors.toSet());
            if (values.isEmpty()) {
                throw consentDenied();
            }
            return values;
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
