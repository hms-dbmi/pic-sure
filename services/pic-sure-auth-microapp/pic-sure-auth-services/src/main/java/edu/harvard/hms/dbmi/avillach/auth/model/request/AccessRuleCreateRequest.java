package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessRuleCreateRequest(
    @NotBlank String name, String description, @NotNull Integer type, @NotBlank String rule, String value, Boolean checkMapKeyOnly,
    Boolean checkMapNode, Boolean evaluateOnlyByGates, Boolean gateAnyRelation, @Valid Set<EntityIdRef> gates,
    @Valid Set<EntityIdRef> subAccessRule
) {
}
