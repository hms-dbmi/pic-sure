package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * An access rule as the access rule endpoints return it, and as it is nested in a privilege. Every member is always written, so a member
 * the row does not hold is {@code null} on the wire. The entity's {@code mergedValues} and {@code mergedName} are not members: they are
 * working state of rule evaluation and never leave the service.
 *
 * @param uuid the row identifier
 * @param name the rule name
 * @param description a free-text description
 * @param type the rule type, one of the values {@code GET /accessRule/allTypes} lists
 * @param rule the JSON path the rule evaluates
 * @param value the value the rule compares against
 * @param gates the rules that gate this one
 * @param gateAnyRelation whether any gate passing is enough, rather than all
 * @param evaluateOnlyByGates whether the rule passes on its gates alone
 * @param subAccessRule the rules evaluated as part of this one
 * @param checkMapNode whether a map node is checked
 * @param checkMapKeyOnly whether only the keys of a map node are checked
 */
@Schema(
    description = "A rule evaluated against a request to decide whether a privilege permits it. Every member is always present; a "
        + "member the rule does not set is null."
)
public record AccessRuleResponse(
    @Schema(
        description = "Row identifier of the access rule.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(description = "Name of the access rule.", example = "AR_ONLY_SEARCH", requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(
        description = "Free-text description of what the rule allows.", example = "Allows search requests only",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String description,
    @Schema(
        description = "How the value found at the rule's JSON path is compared, as one of the numbers GET /accessRule/allTypes lists.",
        example = "4", requiredMode = Schema.RequiredMode.REQUIRED
    ) Integer type,
    @Schema(
        description = "JSON path evaluated against the request to find the value the rule checks.", example = "$.path",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String rule,
    @Schema(
        description = "Value the rule compares the request against.", example = "/search/", requiredMode = Schema.RequiredMode.REQUIRED
    ) String value,
    @Schema(
        description = "Rules that must pass before this rule is evaluated, each in this same shape. Null on a rule created without "
            + "gates and not yet reloaded.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<AccessRuleResponse> gates,
    @Schema(
        description = "True when any one gate passing is enough, false when every gate must pass.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Boolean gateAnyRelation,
    @Schema(
        description = "True when the rule passes or fails on its gates alone and its own path and value are not evaluated.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Boolean evaluateOnlyByGates,
    @Schema(
        description = "Rules that must all pass together with this one, each in this same shape. Null on a rule created without "
            + "sub-rules and not yet reloaded.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<AccessRuleResponse> subAccessRule,
    @Schema(
        description = "True when a map found at the JSON path is walked node by node.", requiredMode = Schema.RequiredMode.REQUIRED
    ) Boolean checkMapNode,
    @Schema(
        description = "True when only the keys of a walked map are checked and its child nodes are skipped.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Boolean checkMapKeyOnly
) {

    /**
     * Copies a persisted access rule, its gates and its sub-rules into their response shape.
     *
     * @param accessRule the persisted access rule
     * @return the response record
     */
    public static AccessRuleResponse from(AccessRule accessRule) {
        return new AccessRuleResponse(
            accessRule.getUuid(), accessRule.getName(), accessRule.getDescription(), accessRule.getType(), accessRule.getRule(),
            accessRule.getValue(), fromAll(accessRule.getGates()), accessRule.getGateAnyRelation(), accessRule.getEvaluateOnlyByGates(),
            fromAll(accessRule.getSubAccessRule()), accessRule.getCheckMapNode(), accessRule.getCheckMapKeyOnly()
        );
    }

    /**
     * Copies a collection of persisted access rules in its iteration order.
     *
     * @param accessRules the persisted access rules, or {@code null}
     * @return the response records in the same order, or {@code null} when {@code accessRules} is {@code null}
     */
    public static List<AccessRuleResponse> fromAll(Collection<AccessRule> accessRules) {
        if (accessRules == null) {
            return null;
        }
        return accessRules.stream().map(AccessRuleResponse::from).toList();
    }
}
