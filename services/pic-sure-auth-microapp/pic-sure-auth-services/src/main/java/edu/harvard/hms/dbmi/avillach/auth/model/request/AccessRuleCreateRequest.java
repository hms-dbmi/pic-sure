package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Body for {@code POST /accessRule}. {@code gates} and {@code subAccessRule} reference existing rules by UUID. The entity's
 * {@code mergedValues} and {@code mergedName} are deliberately absent: they are transient authorization-merge state built during rule
 * evaluation, never client input.
 */
public record AccessRuleCreateRequest(
    @NotBlank String name, String description, @NotNull Integer type, @NotBlank String rule, String value, Boolean checkMapKeyOnly,
    Boolean checkMapNode, Boolean evaluateOnlyByGates, Boolean gateAnyRelation, @Valid Set<EntityIdRef> gates,
    @Valid Set<EntityIdRef> subAccessRule
) {
}
