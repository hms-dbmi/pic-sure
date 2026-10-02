package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A privilege as the privilege endpoints return it, and as it is nested in a role and in an application. A member that is {@code null} or
 * empty is left off the wire. {@code accessRules} comes before {@code application} because that is the order the entity serializes in.
 *
 * @param uuid the row identifier
 * @param name the privilege name
 * @param description a free-text description
 * @param accessRules the access rules the privilege holds
 * @param application the application the privilege belongs to
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "A named permission that roles grant to users. A member that is null or empty is absent.")
public record PrivilegeResponse(
    @Schema(
        description = "Row identifier of the privilege.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid, @Schema(description = "Unique name of the privilege.", example = "PRIV_FENCE_phs000007_c1") String name,
    @Schema(
        description = "Free-text description of what the privilege grants.", example = "Access to phs000007 consent group c1"
    ) String description,
    @Schema(
        description = "Access rules a request must satisfy for this privilege to permit it. Absent when the privilege holds none."
    ) List<AccessRuleResponse> accessRules,
    @Schema(
        description = "The application the privilege belongs to, without its url and privileges. Absent when the privilege has none."
    ) ApplicationResponse application
) {

    /**
     * Copies a persisted privilege, its access rules and its owning application into their response shape.
     *
     * @param privilege the persisted privilege
     * @return the response record
     */
    public static PrivilegeResponse from(Privilege privilege) {
        return new PrivilegeResponse(
            privilege.getUuid(), privilege.getName(), privilege.getDescription(), AccessRuleResponse.fromAll(privilege.getAccessRules()),
            ApplicationResponse.ownerOfPrivilege(privilege.getApplication())
        );
    }

    /**
     * Copies a collection of persisted privileges in its iteration order.
     *
     * @param privileges the persisted privileges, or {@code null}
     * @return the response records in the same order, or {@code null} when {@code privileges} is {@code null}
     */
    public static List<PrivilegeResponse> fromAll(Collection<Privilege> privileges) {
        if (privileges == null) {
            return null;
        }
        return privileges.stream().map(PrivilegeResponse::from).toList();
    }
}
