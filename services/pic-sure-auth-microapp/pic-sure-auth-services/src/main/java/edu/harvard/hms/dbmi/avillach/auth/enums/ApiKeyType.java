package edu.harvard.hms.dbmi.avillach.auth.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The kinds of open-access credential. USER and PLATFORM keys are stored in {@code api_key}. SESSION is a stateless open-access session
 * token issued by {@code OpenSessionService}; it appears only in the {@code /open/validate} answer and is never generated, stored, or
 * listed as an API key.
 */
@Schema(description = "The kind of open-access credential.")
public enum ApiKeyType {

    @Schema(description = "A key an anonymous user generated for themselves.")
    USER,

    @Schema(description = "A key an admin minted for a deployment or partner.")
    PLATFORM,

    @Schema(description = "A stateless open-access session token, never stored as an API key.")
    SESSION
}
