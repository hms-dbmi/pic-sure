package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessRuleUpdateRequest(
    @NotNull UUID uuid, String name, String description, Integer type, String rule, String value, Boolean checkMapKeyOnly,
    Boolean checkMapNode, Boolean evaluateOnlyByGates, Boolean gateAnyRelation, @Valid Set<EntityIdRef> gates,
    @Valid Set<EntityIdRef> subAccessRule
) {
}
