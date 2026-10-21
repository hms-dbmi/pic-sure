package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The body of a successful {@code POST /authentication/{idpProvider}}. Every member is a string, because the providers build this response
 * as a {@code Map<String, String>}: {@code acceptedTOS} is the text {@code "true"} or {@code "false"}, not a boolean.
 *
 * <p>The components are declared in the order the providers' {@code HashMap} emitted them, which is fixed by the keys' hash codes, so a
 * response serializes its members in that fixed order. {@code oktaIdToken} is present only when an Okta-brokered provider (RAS, AIM-AHEAD)
 * answered; the other members are always present, {@code email} as {@code null} when the user has none.</p>
 *
 * @param acceptedTOS whether the user has accepted the current terms of service, as the text {@code "true"} or {@code "false"}
 * @param oktaIdToken the Okta ID token the browser keeps for logout, or {@code null} when the provider is not Okta-brokered
 * @param userId the subject the identity provider knows the user by
 * @param uuid the user's row identifier
 * @param email the user's email, or {@code null}
 * @param token the PIC-SURE session token
 * @param expirationDate when the session token expires, as an ISO instant
 */
@Schema(
    description = "A completed login. Every member is a string; acceptedTOS is the text \"true\" or \"false\". oktaIdToken is present "
        + "only for an Okta-brokered provider (RAS, AIM-AHEAD); email is present and null when the user has no email."
)
public record AuthenticationResponse(
    @Schema(
        description = "Whether the user has accepted the current terms of service, as the text \"true\" or \"false\".", example = "true",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String acceptedTOS,
    @Schema(
        description = "Okta ID token the browser keeps for logout. Present only when an Okta-brokered provider (RAS, AIM-AHEAD) answered.",
        example = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIwMHUxMjM0NSJ9.c2lnbmF0dXJl"
    ) @JsonInclude(JsonInclude.Include.NON_NULL) String oktaIdToken,
    @Schema(
        description = "Subject the identity provider knows the user by.", example = "fence|12345",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String userId,
    @Schema(
        description = "Row identifier of the user.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String uuid,
    @Schema(
        description = "Email address of the user. Always present; null when the user has none.", example = "researcher@example.org",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String email,
    @Schema(
        description = "PIC-SURE session token to send as the bearer token.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXwxMjM0NSJ9.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String token,
    @Schema(
        description = "When the session token expires, as an ISO instant.", example = "2026-09-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String expirationDate
) {

    /**
     * Copies the map a provider returned into the response shape.
     *
     * @param login the provider's response map
     * @return the response record
     */
    public static AuthenticationResponse from(Map<String, String> login) {
        return new AuthenticationResponse(
            login.get("acceptedTOS"), login.get("oktaIdToken"), login.get("userId"), login.get("uuid"), login.get("email"),
            login.get("token"), login.get("expirationDate")
        );
    }
}
