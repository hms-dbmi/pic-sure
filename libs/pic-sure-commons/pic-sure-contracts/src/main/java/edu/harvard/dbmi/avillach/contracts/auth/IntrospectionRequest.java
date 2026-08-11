package edu.harvard.dbmi.avillach.contracts.auth;


/**
 * The body the gateway POSTs to PSAMA's token-introspection endpoint. The wrapper keys are load-bearing: deployed access rules are anchored
 * at the {@code request} node, so renaming or re-nesting either key leaves every rule unresolvable.
 */
public record IntrospectionRequest(String token, TargetedRequest request) {
}
