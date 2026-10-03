package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One access rule in the body of {@code PUT /accessRule}. Every member except {@code uuid} is optional, and a member left out leaves the
 * stored value unchanged. As on create, {@code mergedValues} and {@code mergedName} are not members.
 *
 * @param uuid the UUID of the rule to update
 * @param name the new name
 * @param description the new description
 * @param type the new rule type
 * @param rule the new JSON path
 * @param value the new comparison value
 * @param checkMapKeyOnly the new map-key-only flag
 * @param checkMapNode the new map-node flag
 * @param evaluateOnlyByGates the new gates-only flag
 * @param gateAnyRelation the new any-gate flag
 * @param gates the existing rules that should gate this one, by UUID
 * @param subAccessRule the existing rules that should be evaluated as part of this one, by UUID
 */
@Schema(description = "One access rule to update, named by UUID. A member left out keeps its stored value.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessRuleUpdateRequest(
    @Schema(
        description = "UUID of the access rule to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New name of the access rule.", example = "AR_ONLY_SEARCH") String name,
    @Schema(description = "New free-text description of what the rule allows.", example = "Allows search requests only") String description,
    @Schema(
        description = "New way the value found at the rule's JSON path is compared, as one of the numbers GET /accessRule/allTypes lists.",
        example = "4"
    ) Integer type,
    @Schema(description = "New JSON path evaluated against the request to find the value the rule checks.", example = "$.path") String rule,
    @Schema(description = "New value the rule compares the request against.", example = "/search/") String value,
    @Schema(description = "True to check only the keys of a walked map and skip its child nodes.") Boolean checkMapKeyOnly,
    @Schema(description = "True to walk a map found at the JSON path node by node.") Boolean checkMapNode,
    @Schema(
        description = "True to pass or fail the rule on its gates alone, without evaluating its own path and value."
    ) Boolean evaluateOnlyByGates,
    @Schema(description = "True when any one gate passing is enough, false when every gate must pass.") Boolean gateAnyRelation,
    @Schema(
        description = "Existing access rules that must pass before this rule is evaluated, each named by UUID. When present, it replaces the stored set."
    ) @Valid Set<EntityIdRef> gates,
    @Schema(
        description = "Existing access rules that must all pass together with this one, each named by UUID. When present, it replaces the stored set."
    ) @Valid Set<EntityIdRef> subAccessRule
) {
}
