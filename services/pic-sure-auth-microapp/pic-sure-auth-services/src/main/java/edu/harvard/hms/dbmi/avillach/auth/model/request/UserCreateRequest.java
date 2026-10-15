package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * One user in the body of {@code POST /user}. The row identifier is generated on persist, and everything an identity provider or a login
 * flow owns is absent: {@code subject}, {@code passport}, the long-term {@code token}, {@code acceptedTOS}, {@code matched} and
 * {@code auth0metadata}.
 *
 * @param email the user's email; when absent it is read from an email key in {@code generalMetadata}
 * @param active whether the user is active; absent means active
 * @param generalMetadata a JSON object of profile metadata
 * @param connection the existing connection the user signs in through
 * @param roles existing roles to grant, by UUID; at least one is required
 */
@Schema(
    description = "One user to create. The server generates the identifier, and the subject, long-term token and passport are set only when the user signs in."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserCreateRequest(
    @Schema(
        description = "Email address of the user. When left out it is read from an email key in generalMetadata.",
        example = "researcher@example.org"
    ) String email, @Schema(description = "Whether the user may sign in. Absent means true.") Boolean active,
    @Schema(
        description = "Profile metadata of the user. The value is one string holding a JSON object.",
        example = "{\"email\":\"researcher@example.org\"}"
    ) String generalMetadata,
    @Schema(
        description = "The existing connection the user signs in through, named by its business identifier."
    ) @Valid ConnectionRef connection,
    @Schema(
        description = "Existing roles to grant the user, each named by UUID. At least one is required.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotEmpty @Valid Set<EntityIdRef> roles
) {
}
