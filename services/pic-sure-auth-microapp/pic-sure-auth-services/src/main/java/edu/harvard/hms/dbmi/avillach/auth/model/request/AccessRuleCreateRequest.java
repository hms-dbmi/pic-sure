package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * One access rule in the body of {@code POST /accessRule}. The row identifier is generated on persist. The entity's {@code mergedValues}
 * and {@code mergedName} are absent: they are transient state built while rules are evaluated, never client input. An absent boolean flag
 * is stored as {@code false}.
 *
 * @param name the rule name
 * @param description a free-text description
 * @param type the rule type, one of the values {@code GET /accessRule/allTypes} lists
 * @param rule the JSON path the rule evaluates
 * @param value the value the rule compares against
 * @param checkMapKeyOnly whether only the keys of a map node are checked
 * @param checkMapNode whether a map node is checked
 * @param evaluateOnlyByGates whether the rule passes on its gates alone
 * @param gateAnyRelation whether any gate passing is enough, rather than all
 * @param gates existing rules that gate this one, by UUID
 * @param subAccessRule existing rules evaluated as part of this one, by UUID
 */
@Schema(description = "One access rule to create. The server generates its identifier.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessRuleCreateRequest(
    @Schema(
        description = "Name of the access rule.", example = "AR_ONLY_SEARCH", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String name,
    @Schema(description = "Free-text description of what the rule allows.", example = "Allows search requests only") String description,
    @Schema(
        description = "How the value found at the rule's JSON path is compared, as one of the numbers GET /accessRule/allTypes lists.",
        example = "4", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull Integer type,
    @Schema(
        description = "JSON path evaluated against the request to find the value the rule checks.", example = "$.path",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String rule, @Schema(description = "Value the rule compares the request against.", example = "/search/") String value,
    @Schema(
        description = "True to check only the keys of a walked map and skip its child nodes. Absent means false."
    ) Boolean checkMapKeyOnly,
    @Schema(description = "True to walk a map found at the JSON path node by node. Absent means false.") Boolean checkMapNode,
    @Schema(
        description = "True to pass or fail the rule on its gates alone, without evaluating its own path and value. Absent means false."
    ) Boolean evaluateOnlyByGates,
    @Schema(
        description = "True when any one gate passing is enough, false when every gate must pass. Absent means false."
    ) Boolean gateAnyRelation,
    @Schema(
        description = "Existing access rules that must pass before this rule is evaluated, each named by UUID."
    ) @Valid Set<EntityIdRef> gates,
    @Schema(
        description = "Existing access rules that must all pass together with this one, each named by UUID."
    ) @Valid Set<EntityIdRef> subAccessRule
) {
}
