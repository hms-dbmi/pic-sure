package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The body of {@code POST /authentication/{idpProvider}}: every key any identity provider implementation reads. Which keys a login carries
 * depends on the provider named in the path, so every member is optional and the provider decides what is missing.
 *
 * @param code the authorization code an OAuth code flow returns to the browser, read by the FENCE, RAS and AIM-AHEAD providers
 * @param accessToken the access token an implicit flow returns to the browser, read by the Auth0 provider as {@code access_token}
 * @param redirectURI the redirect URI the browser signed in with, read by the Auth0 provider
 * @param persona the persona a mock login names; no provider in this service reads it
 */
@Schema(
    description = "The identity provider login to exchange for a PIC-SURE token. Which members a login carries depends on the provider "
        + "named in the path, so every member is optional. Any other member is ignored."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationRequest(
    @Schema(
        description = "Authorization code returned to the browser by an OAuth code flow. Read by the FENCE, RAS and AIM-AHEAD providers.",
        example = "SplxlOBeZQQYbYS6WxSbIA"
    ) String code,
    @Schema(
        description = "Access token returned to the browser by an implicit flow. Read by the Auth0 provider.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXwxMjM0NSJ9.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    ) @JsonProperty("access_token") String accessToken,
    @Schema(
        description = "Redirect URI the browser signed in with. Read by the Auth0 provider.", example = "https://localhost/login/loading"
    ) String redirectURI,
    @Schema(description = "Persona a mock login names. No provider in this service reads it.", example = "researcher") String persona
) {

    /**
     * Returns the members a provider can read, keyed by their wire names, leaving out the ones the login did not send. Providers look keys
     * up with {@code get} and {@code containsKey}, so an absent member and a member sent as {@code null} read the same.
     *
     * @return the members that were sent, in declaration order
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (code != null) {
            map.put("code", code);
        }
        if (accessToken != null) {
            map.put("access_token", accessToken);
        }
        if (redirectURI != null) {
            map.put("redirectURI", redirectURI);
        }
        if (persona != null) {
            map.put("persona", persona);
        }
        return map;
    }
}
