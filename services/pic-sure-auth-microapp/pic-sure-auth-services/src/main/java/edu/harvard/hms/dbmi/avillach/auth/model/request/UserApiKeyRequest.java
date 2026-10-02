package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /open/apiKey}: the CAPTCHA proof and the optional contact details of an anonymous user asking for a key.
 *
 * @param captchaToken the token the CAPTCHA widget issued, required when CAPTCHA verification is on
 * @param name the requester's name, or {@code null}
 * @param email the requester's email, or {@code null}
 */
@Schema(description = "A request for an open-access USER API key. Any other member is ignored.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserApiKeyRequest(
    @Schema(
        description = "Token the CAPTCHA widget issued for this submission. Required when CAPTCHA verification is on.",
        example = "03AFcWeA5p4xG1mZ9n"
    ) String captchaToken,
    @Schema(description = "Name of the requester, at most 255 characters. Optional.", example = "Jane Doe") String name,
    @Schema(
        description = "Email address of the requester, at most 255 characters. Optional.", example = "researcher@example.org"
    ) String email
) {
}
