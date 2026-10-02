package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The rule types an access rule may use, as {@code GET /accessRule/allTypes} returns them.
 *
 * @param types each rule type name mapped to the number an access rule stores in its {@code type}
 */
@Schema(description = "The comparison types an access rule may use.")
public record AccessRuleTypes(
    @Schema(
        description = "Each key is the name of a comparison type, such as ALL_EQUALS or ANY_CONTAINS, and its value is the number an "
            + "access rule carries in its type member.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, Integer> types
) {
}
