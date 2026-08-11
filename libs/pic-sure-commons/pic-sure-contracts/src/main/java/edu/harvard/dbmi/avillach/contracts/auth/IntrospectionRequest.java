package edu.harvard.dbmi.avillach.contracts.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body the gateway POSTs to PSAMA's token-introspection endpoint. The wrapper keys are load-bearing: deployed access rules are anchored
 * at the {@code request} node, so renaming or re-nesting either key leaves every rule unresolvable.
 */
@Schema(description = "Token-introspection request sent by the gateway to PSAMA on every authorized call")
public record IntrospectionRequest(
    @Schema(description = "The end user's bearer token, without the Bearer prefix") String token,
    @Schema(description = "The request PSAMA is being asked to authorize") TargetedRequest request
) {
}
