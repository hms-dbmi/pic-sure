package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /authentication/{idpProvider}}, replacing the untyped {@code Map<String, String>} the endpoint used to bind.
 *
 * <p>The union of every key the shipped identity providers read, enumerated from their {@code authenticate} implementations: <ul>
 * <li>{@code code} -- OIDC authorization code; read by {@code AimAheadAuthenticationService}, {@code RASAuthenticationService} and
 * {@code FENCEAuthenticationService}</li> <li>{@code access_token} + {@code redirectURI} -- read by {@code Auth0AuthenticationService}</li>
 * </ul> A provider is handed the whole record and reads the field it needs; the fields it does not use are null. Snake_case
 * {@code access_token} is pinned with {@code @JsonProperty} because that is the key Auth0 clients already send.
 *
 * <p>Tolerant reader on purpose. PSAMA's MVC ObjectMapper is a plain {@code new ObjectMapper()} (see
 * {@code ApplicationConfig#objectMapper}), so {@code FAIL_ON_UNKNOWN_PROPERTIES} is ON and the previous {@code Map} binding -- which can
 * never have an unknown property -- was effectively lenient. Without {@code ignoreUnknown} this retyping would turn any extra key a login
 * client sends (a provider-specific field, a stale UI parameter) into a 400 that locks users out; login is not a place to discover that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Credentials handed to an identity provider by POST /authentication/{idpProvider}")
public record AuthenticationRequest(
    @Schema(description = "OIDC authorization code (Okta/AIM-AHEAD, RAS, FENCE)") String code,
    @JsonProperty("access_token") @Schema(name = "access_token", description = "Auth0 access token") String accessToken,
    @Schema(description = "Auth0 redirect URI the code was issued against") String redirectURI
) {
}
