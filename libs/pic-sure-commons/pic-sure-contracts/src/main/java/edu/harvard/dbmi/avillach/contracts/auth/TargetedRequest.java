package edu.harvard.dbmi.avillach.contracts.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The node deployed FISMA access rules are evaluated against. PSAMA stores those rules as JsonPath strings in its database and reads them
 * with {@code JsonPath.parse(request).read(rule)}; they are data, not code, so this shape cannot be renamed or restructured without
 * silently changing production authorization decisions. {@code RuleCompatibilityTest} is the guard.
 */
@Schema(
    name = "TargetedRequest",
    description = "What the gateway asks PSAMA to authorize: the request path plus, when there is one, the body being authorized. "
        + "Deployed JsonPath access rules bind to this node"
)
public record TargetedRequest(
    // Omitted rather than emitted as null for the same reason as query below, plus a sharper one: PSAMA's AccessRuleService#evaluateNode
    // dereferences the matched value eagerly, so a present-but-null match is an NPE -> 500 -> gateway 502, where an absent key is a clean
    // decision. An emitted "Target Service": null would turn every rule bound to it into a 500.
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("Target Service") @Schema(
        name = "Target Service", description = "Verbatim request path; deployed JsonPath access rules bind $.['Target Service']"
    ) String targetService,
    // Omitted entirely -- never null -- when there is nothing to authorize: PSAMA's extractAndCheckRule treats a PathNotFoundException
    // differently from a null match, so an emitted "query": null would change access decisions.
    @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(
        description = "The request body being authorized; bare v3 Query on query paths. Absent when the request has no body to authorize"
    ) JsonNode query
) {
}
