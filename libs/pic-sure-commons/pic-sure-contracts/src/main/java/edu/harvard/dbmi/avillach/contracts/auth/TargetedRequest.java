package edu.harvard.dbmi.avillach.contracts.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The node deployed FISMA access rules are evaluated against. PSAMA stores those rules as JsonPath strings in its database and reads them
 * with {@code JsonPath.parse(request).read(rule)}; they are data, not code, so this shape cannot be renamed or restructured without
 * silently changing production authorization decisions. {@code RuleCompatibilityTest} is the guard.
 *
 * <p>A TOLERANT READER, deliberately: this is the node BOTH sides of the authorization hop bind, and it is reached on the unauthenticated
 * open-access path as well as on introspection. The map it replaced could not have an unknown property, and the legacy WAR put keys here
 * that the contract does not model ({@code resourceUUID}); rejecting one of those turns an authorization question into a 400, which both
 * callers fail closed on. Tolerance is scoped to THIS node only -- {@link IntrospectionRequest}, the top-level introspection body,
 * deliberately keeps no {@code ignoreUnknown}, so an unknown field there is still rejected at the ingress. Both halves of that boundary are
 * pinned by {@code TokenControllerInspectContractTest}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TargetedRequest(
    // Omitted rather than emitted as null for the same reason as query below, plus a sharper one: PSAMA's AccessRuleService#evaluateNode
    // dereferences the matched value eagerly, so a present-but-null match is an NPE -> 500 -> gateway 502, where an absent key is a clean
    // decision. An emitted "Target Service": null would turn every rule bound to it into a 500.
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("Target Service") String targetService,
    // Omitted entirely -- never null -- when there is nothing to authorize: PSAMA's extractAndCheckRule treats a PathNotFoundException
    // differently from a null match, so an emitted "query": null would change access decisions.
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode query
) {
}
