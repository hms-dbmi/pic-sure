package edu.harvard.hms.dbmi.avillach.auth.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The kind of open-access API key.")
public enum ApiKeyType {

    @Schema(description = "A key an anonymous user generated for themselves.")
    USER,

    @Schema(description = "A key an admin minted for a deployment or partner.")
    PLATFORM
}
