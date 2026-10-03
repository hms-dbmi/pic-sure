package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;
import java.util.UUID;

/**
 * The body of {@code GET /user/me}: the caller's own profile, with the long-term token the caller uses from scripts. A member that is
 * {@code null} or empty is left off the wire, so a user with no privileges has no {@code privileges} member.
 *
 * @param uuid the user's row identifier
 * @param email the user's email
 * @param privileges the names of every privilege the user's roles grant
 * @param token the user's long-term token, issued on first read
 * @param acceptedTOS whether the user has accepted the current terms of service
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(
    description = "The caller's own profile. A member that is null or empty is absent, so a user with no privileges has no privileges "
        + "member. The long-term token is always included; the hasToken query parameter is accepted and has no effect."
)
public record UserProfileResponse(
    @Schema(
        description = "Row identifier of the user.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(description = "Email address of the user. Absent when the user has none.", example = "researcher@example.org") String email,
    @Schema(
        description = "Names of every privilege the user's roles grant. Absent when there are none.",
        example = "[\"SUPER_ADMIN\", \"PRIV_FENCE_phs000007_c1\"]"
    ) Set<String> privileges,
    @Schema(
        description = "The user's long-term token, for use from scripts and the adapters.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJMT05HX1RFUk1fVE9LRU58ZmVuY2V8MTIzNDUifQ.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String token,
    @Schema(
        description = "Whether the user has accepted the current terms of service.", requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean acceptedTOS
) {
}
