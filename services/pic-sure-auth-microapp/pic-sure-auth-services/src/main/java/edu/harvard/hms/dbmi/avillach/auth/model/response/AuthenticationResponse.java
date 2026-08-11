package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /authentication/{idpProvider}}, replacing the {@code HashMap<String, String>} every identity provider used to
 * hand back. The field set and the JSON key names are transcribed verbatim from {@code UserService#getUserProfileResponse} plus the one key
 * the Okta-brokered providers add ({@code oktaIdToken}), so the wire shape is unchanged.
 *
 * <p>{@code acceptedTOS} is a {@code String} rather than a {@code boolean} on purpose: the map put {@code "" + acceptedTOS}, so the wire
 * has always carried {@code "acceptedTOS":"true"}. Narrowing it to a JSON boolean would flip a client doing a truthiness check on the
 * string {@code "false"} (which is truthy in JavaScript) from "accepted" to "not accepted" -- a change that only shows up as users being
 * re-prompted for terms of service. Retyping it is a follow-up that needs the frontend in the same change.
 *
 * <p>{@code NON_NULL} reproduces the map's behavior of simply not having a key: {@code oktaIdToken} was absent for Auth0 and FENCE logins
 * and stays absent here rather than appearing as {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The authenticated user's PIC-SURE token and profile")
public record AuthenticationResponse(
    @Schema(description = "The PIC-SURE JWT the client sends as its bearer token") String token,
    @Schema(description = "The user's subject claim; also the session key") String userId,
    @Schema(description = "The user's email address") String email,
    @Schema(description = "Whether the user accepted the latest terms of service, as the string \"true\"/\"false\"") String acceptedTOS,
    @Schema(description = "ISO-8601 UTC instant at which the token expires") String expirationDate,
    @Schema(description = "The user's PSAMA uuid") String uuid,
    @Schema(description = "Okta id_token, present only for the Okta-brokered providers (AIM-AHEAD, RAS)") String oktaIdToken
) {

    /** The Okta-brokered providers mint the profile first and attach the IdP's id token afterwards. */
    public AuthenticationResponse withOktaIdToken(String oktaIdToken) {
        return new AuthenticationResponse(token, userId, email, acceptedTOS, expirationDate, uuid, oktaIdToken);
    }
}
