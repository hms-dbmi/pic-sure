package edu.harvard.hms.dbmi.avillach.gateway.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IntrospectionResponse(
    boolean active,
    // PSAMA's inspect response carries the user UUID as "uuid" (UserService#responseMap / UserClaims#toMap); it never
    // sends a "userId" field. Without this alias X-User-Id is never propagated and the query/operations services'
    // header-based authn rejects every request.
    @JsonAlias("uuid") String userId, String sub, String email, String roles, List<String> privileges, Boolean tokenRefreshed, String token,
    String query, String message
) {

    public IntrospectionResponse(
        boolean active, String userId, String sub, String email, String roles, List<String> privileges, Boolean tokenRefreshed,
        String token, String query
    ) {
        this(active, userId, sub, email, roles, privileges, tokenRefreshed, token, query, null);
    }
}
