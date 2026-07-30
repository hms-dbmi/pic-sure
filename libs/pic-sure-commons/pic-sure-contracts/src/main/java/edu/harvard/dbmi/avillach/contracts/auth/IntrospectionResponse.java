package edu.harvard.dbmi.avillach.contracts.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * PSAMA's answer to {@link IntrospectionRequest}: whether the token is usable, who it belongs to, and -- when consent evaluation rewrote it
 * -- the query the caller is actually allowed to run. <p> Unknown properties are ignored because PSAMA copies every JWT claim into this
 * response ({@code TokenInspection#addAllFields}), so the payload carries {@code exp}, {@code iat}, {@code jti}, {@code message} and
 * whatever else the token happened to hold. Only the fields below are contractual; nothing may start depending on the rest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// SECURITY: absence and null are NOT interchangeable on this record, because Jackson deserializes an explicit JSON null into a JsonNode
// component as NullNode, not as Java null -- only an ABSENT field yields Java null. Callers test the mutated query with
// `query() != null` (PsamaIntrospectionFilter), so emitting "query": null on every non-mutated response would make that check fire every
// time and swap the caller's body for a NullNode. NON_NULL keeps the pre-contract wire behaviour: the query key appears only when consent
// evaluation actually rewrote the query.
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "PSAMA's token-introspection verdict, including the consent-mutated query the caller may actually run")
public record IntrospectionResponse(
    @Schema(description = "False whenever the token is unusable for any reason: invalid, expired, unknown user, or unauthorized") //
    boolean active,
    // PSAMA emits this as "userId" now that it writes this record. It spent years emitting the same value as "uuid" (copied from the JWT
    // claims), and any PSAMA that has not been redeployed still does, so the alias stays: without it X-User-Id is never propagated and the
    // query/operations services' header-based authn rejects every request.
    @JsonAlias("uuid") @Schema(description = "PSAMA's UUID for the authenticated user; also read from the legacy \"uuid\" key") //
    String userId, @Schema(description = "Token subject, i.e. the identity-provider-scoped user identifier") String sub,
    @Schema(description = "Email address of the authenticated user") String email,
    @Schema(description = "Names of the roles held by the user") List<String> roles,
    @Schema(description = "Names of the privileges the user holds in the calling application, plus their application-independent ones") //
    List<String> privileges,
    @Schema(description = "True when PSAMA issued a fresh token because the presented one was close to expiring") boolean tokenRefreshed,
    @Schema(description = "The refreshed token; populated only when tokenRefreshed is true") String token,
    @Schema(
        description = "Consent-mutated v3 Query as a JSON OBJECT (never a string). Present only when access-rule evaluation rewrote the "
            + "submitted query; the caller must run this one instead of the query it sent"
    ) JsonNode query
) {
}
