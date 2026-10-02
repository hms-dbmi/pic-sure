package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A user as the admin user endpoints return it. A member that is {@code null} or empty is left off the wire. The entity's long-term
 * {@code token}, {@code passport} and {@code auth0metadata} are not members: they are credentials and identity provider state, and no admin
 * screen reads them.
 *
 * @param uuid the row identifier
 * @param subject the subject the identity provider knows the user by
 * @param roles the roles the user holds
 * @param email the user's email
 * @param connection the connection the user signs in through
 * @param matched whether the user has been matched to an identity provider profile
 * @param acceptedTOS when the user accepted the terms of service
 * @param generalMetadata a JSON object of profile metadata, carried as a string
 * @param active whether the user may sign in
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(
    description = "A user as an administrator sees it. The long-term token, passport and identity provider metadata are never part of "
        + "this shape. A member that is null or empty is absent."
)
public record UserResponse(
    @Schema(
        description = "Row identifier of the user.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "Subject the identity provider knows the user by. Absent until the user first signs in.", example = "fence|12345"
    ) String subject,
    @Schema(description = "Roles the user holds, each with its privileges. Absent when the user holds none.") List<RoleResponse> roles,
    @Schema(description = "Email address of the user.", example = "researcher@example.org") String email,
    @Schema(description = "The connection the user signs in through. Absent when the user has none.") ConnectionResponse connection,
    @Schema(
        description = "Whether the user has been matched to an identity provider profile.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean matched,
    @Schema(
        description = "When the user accepted the terms of service, in milliseconds since the Unix epoch. Absent until the user "
            + "accepts them.",
        implementation = Long.class, example = "1790777100000"
    ) Date acceptedTOS,
    @Schema(
        description = "Profile metadata of the user. The value is one string holding a JSON object.",
        example = "{\"email\":\"researcher@example.org\"}"
    ) String generalMetadata,
    @Schema(description = "Whether the user may sign in.", requiredMode = Schema.RequiredMode.REQUIRED) boolean active
) {

    /**
     * Copies a persisted user, its roles and its connection into their response shape.
     *
     * @param user the persisted user
     * @return the response record
     */
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getUuid(), user.getSubject(), RoleResponse.fromAll(user.getRoles()), user.getEmail(),
            ConnectionResponse.from(user.getConnection()), user.isMatched(), user.getAcceptedTOS(), user.getGeneralMetadata(),
            user.isActive()
        );
    }
}
