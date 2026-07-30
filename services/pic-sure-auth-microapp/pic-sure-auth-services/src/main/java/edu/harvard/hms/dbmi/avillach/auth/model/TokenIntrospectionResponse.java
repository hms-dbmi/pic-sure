package edu.harvard.hms.dbmi.avillach.auth.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;

/**
 * What {@code POST /token/inspect} actually writes: the {@link IntrospectionResponse} contract, flattened onto the top level, plus the
 * unmodelled {@code message} PSAMA has always sent to explain a denial ("Token not found", "user doesn't exist", "User doesn't have enough
 * privileges."). <p> The contract deliberately does not model {@code message} -- no caller may branch on its wording -- but dropping it
 * would erase the only human-readable reason a token was rejected from both the wire and PSAMA's own audit metadata. The contract reader is
 * a tolerant reader ({@code ignoreUnknown}), so an extra key costs the gateway nothing. <p> {@code @JsonUnwrapped} is serialization-only
 * here, which is all PSAMA needs: nothing deserializes this type. Consumers bind {@link IntrospectionResponse} instead, and
 * {@code TokenControllerInspectContractTest} proves this type serializes to something that record reads back intact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenIntrospectionResponse(@JsonUnwrapped IntrospectionResponse introspection, String message) {

    public TokenIntrospectionResponse(IntrospectionResponse introspection) {
        this(introspection, null);
    }

    /** Denial with no user context: the token never resolved to one. */
    public static TokenIntrospectionResponse denied(String message) {
        return new TokenIntrospectionResponse(new IntrospectionResponse(false, null, null, null, null, null, false, null, null), message);
    }
}
