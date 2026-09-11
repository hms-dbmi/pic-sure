package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Body for {@code PUT /accessRule}. Every member except {@code uuid} is optional and means "leave unchanged" when absent, matching the
 * endpoint's documented "will only update the fields listed" contract.
 */
public record AccessRuleUpdateRequest(
    @NotNull UUID uuid, String name, String description, Integer type, String rule, String value, Boolean checkMapKeyOnly,
    Boolean checkMapNode, Boolean evaluateOnlyByGates, Boolean gateAnyRelation, @Valid Set<EntityIdRef> gates,
    @Valid Set<EntityIdRef> subAccessRule
) {
}
