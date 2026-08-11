package edu.harvard.dbmi.avillach.contracts.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

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
public record IntrospectionResponse(
    //
    boolean active,
    // PSAMA emits this as "userId" now that it writes this record. It spent years emitting the same value as "uuid" (copied from the JWT
    // claims); the JWT claim keeps that name, but nothing on this wire does. The stack deploys as one unit, so the tolerance the old
    // @JsonAlias("uuid") bought is gone with it -- see TokenService#userId, which reads the claim and writes this field.
    //
    String userId, String sub, String email, List<String> roles, List<String> privileges, boolean tokenRefreshed, String token,
    JsonNode query
) {
}
