package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A role as the role endpoints return it, and as it is nested in a user. A member that is {@code null} or empty is left off the wire.
 *
 * @param uuid the row identifier
 * @param name the role name
 * @param description a free-text description
 * @param privileges the privileges the role grants
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "A named bundle of privileges granted to users. A member that is null or empty is absent.")
public record RoleResponse(
    @Schema(
        description = "Row identifier of the role.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid, @Schema(description = "Name of the role.", example = "PIC-SURE Top Admin") String name,
    @Schema(
        description = "Free-text description of who holds the role.", example = "Manages users, roles and privileges"
    ) String description,
    @Schema(description = "Privileges the role grants. Absent when the role grants none.") List<PrivilegeResponse> privileges
) {

    /**
     * Copies a persisted role and its privileges into their response shape.
     *
     * @param role the persisted role
     * @return the response record
     */
    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getUuid(), role.getName(), role.getDescription(), PrivilegeResponse.fromAll(role.getPrivileges()));
    }

    /**
     * Copies a collection of persisted roles in its iteration order.
     *
     * @param roles the persisted roles, or {@code null}
     * @return the response records in the same order, or {@code null} when {@code roles} is {@code null}
     */
    public static List<RoleResponse> fromAll(Collection<Role> roles) {
        if (roles == null) {
            return null;
        }
        return roles.stream().map(RoleResponse::from).toList();
    }
}
