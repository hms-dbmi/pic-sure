package edu.harvard.dbmi.avillach.contracts.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * PSAMA's answer to {@link IntrospectionRequest}: whether the token is usable, who it belongs to, and -- when consent evaluation rewrote it
 * -- the query the caller is actually allowed to run. <p> Unknown properties are ignored because the body carries more than this record
 * models: PSAMA wraps it in {@code TokenIntrospectionResponse}, which {@code @JsonUnwrapped}s these fields and adds its own unmodelled
 * {@code message}. Tolerant reading also absorbs additive fields a future PSAMA sends before this contract learns about them, which is what
 * lets the two deploy without a flag day. Only the fields below are contractual; nothing may start depending on the rest.
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
    // claims); the JWT claim keeps that name, but nothing on this wire does. The stack deploys as one unit, so the tolerance the old
    // @JsonAlias("uuid") bought is gone with it -- see TokenService#userId, which reads the claim and writes this field.
    @Schema(description = "PSAMA's UUID for the authenticated user") //
    String userId, @Schema(description = "Token subject, i.e. the identity-provider-scoped user identifier") String sub,
    @Schema(description = "Email address of the authenticated user") String email,
    @Schema(
        description = "Names of the BASELINE roles held by the user. PSAMA no longer mints per-study MANAGED_* roles or FENCE-derived "
            + "role names at login, so nothing here describes which studies the user may query: study access flows exclusively through "
            + "user_consents, read via GET /user/me/consents."
    ) List<String> roles,
    @Schema(
        description = "Names of the BASELINE privileges the user holds in the calling application, plus their application-independent "
            + "ones. PSAMA no longer mints per-study PRIV_MANAGED_* privileges or FENCE-derived names at login, so nothing here "
            + "describes which studies the user may query: study access flows exclusively through user_consents, read via "
            + "GET /user/me/consents."
    ) List<String> privileges,
    @Schema(description = "True when PSAMA issued a fresh token because the presented one was close to expiring") boolean tokenRefreshed,
    @Schema(description = "The refreshed token; populated only when tokenRefreshed is true") String token,
    @Schema(
        description = "Consent-mutated v3 Query as a JSON OBJECT (never a string). Present only when access-rule evaluation rewrote the "
            + "submitted query; the caller must run this one instead of the query it sent"
    ) JsonNode query
) {
}
