package edu.harvard.dbmi.avillach.contracts.auth;


import java.util.Map;
import java.util.Set;

/**
 * PSAMA's answer to {@code GET /user/me/consents}: the study authorizations of the caller behind the presented token.
 *
 * <p>This is the SOLE source of study-level access for v3 queries. Roles and privileges are endpoint-level only, so a client that wants to
 * know which studies a user may query reads this, not {@link IntrospectionResponse#roles()}.
 *
 * <p>A user with no stored consent record answers {@code {userId, consents: {}}} rather than an error: an empty map is a normal, expected
 * "nothing authorized", never a failure to retry. The persisted row's own uuid is deliberately NOT on the wire -- it is a storage detail of
 * PSAMA's {@code user_consents} table and no client has ever had a use for it.
 */
public record UserConsentsResponse(String userId, Map<String, Set<String>> consents) {

    public UserConsentsResponse {
        consents = consents == null ? Map.of() : consents;
    }
}
