package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * A registered application as the application endpoints return it, and as it is nested in a privilege. The bearer {@code token} is never a
 * member. A member that is {@code null} or empty is left off the wire, as the entity it replaces left it off.
 *
 * <p>The three factories differ in which optional members they fill, because the endpoints differ today: the reads carry no privileges, the
 * writes carry them, and the application nested in a privilege carries neither privileges nor {@code url}.</p>
 *
 * @param uuid the row identifier
 * @param name the application name
 * @param description a free-text description
 * @param url the application's URL
 * @param enable whether the application is enabled
 * @param privileges the privileges the application owns
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(
    description = "An application registered with the authorization service. Its bearer token is never part of this shape. A member "
        + "that is null or empty is absent."
)
public record ApplicationResponse(
    @Schema(
        description = "Row identifier of the application.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid, @Schema(description = "Unique name of the application.", example = "PICSURE") String name,
    @Schema(description = "Free-text description of the application.", example = "The PIC-SURE API application") String description,
    @Schema(
        description = "URL the application is served from. Absent on the application nested in a privilege.", example = "/picsureui"
    ) String url,
    @Schema(description = "Whether the application may authenticate.", requiredMode = Schema.RequiredMode.REQUIRED) boolean enable,
    @Schema(
        description = "Privileges the application owns. Present only in the create, update and delete responses, and only when the "
            + "application owns at least one."
    ) List<PrivilegeResponse> privileges
) {

    /**
     * Copies a persisted application into the shape the two read endpoints return, without its privileges.
     *
     * @param application the persisted application
     * @return the response record
     */
    public static ApplicationResponse from(Application application) {
        return new ApplicationResponse(
            application.getUuid(), application.getName(), application.getDescription(), application.getUrl(), application.isEnable(), null
        );
    }

    /**
     * Copies a persisted application and the privileges it owns into the shape the create, update and delete endpoints return.
     *
     * @param application the persisted application
     * @return the response record
     */
    public static ApplicationResponse withPrivileges(Application application) {
        return new ApplicationResponse(
            application.getUuid(), application.getName(), application.getDescription(), application.getUrl(), application.isEnable(),
            PrivilegeResponse.fromAll(application.getPrivileges())
        );
    }

    /**
     * Copies the owning application of a privilege into the shape a privilege nests, which carries neither {@code url} nor privileges.
     *
     * @param application the persisted application, or {@code null}
     * @return the response record, or {@code null} when {@code application} is {@code null}
     */
    public static ApplicationResponse ownerOfPrivilege(Application application) {
        if (application == null) {
            return null;
        }
        return new ApplicationResponse(
            application.getUuid(), application.getName(), application.getDescription(), null, application.isEnable(), null
        );
    }
}
