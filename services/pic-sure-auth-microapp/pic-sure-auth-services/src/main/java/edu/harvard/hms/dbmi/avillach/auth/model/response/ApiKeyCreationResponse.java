package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The only place a plaintext API key ever appears. Returned once at creation; the key is not recoverable afterwards.
 */
@Schema(description = "A newly created API key. The plaintext key appears here once and cannot be recovered afterwards.")
public record ApiKeyCreationResponse(
    @Schema(
        description = "The plaintext API key, shown once.", example = "picsure_u_00000000000000000000000000000000000000000003tr27S",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String apiKey,
    @Schema(
        description = "Row identifier of the key, used to revoke it.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The first characters of the key, for recognizing it in listings.", example = "00000000",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String displayPrefix,
    @Schema(description = "Whether the key is a USER or a PLATFORM key.", requiredMode = Schema.RequiredMode.REQUIRED) ApiKeyType keyType,
    @Schema(
        description = "When the key expires, as an ISO instant. Null for a key that never expires.", example = "2026-10-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant expiresAt
) {

    // the default record toString would embed the plaintext key, one accidental log statement away from a leak
    @Override
    public String toString() {
        return "ApiKeyCreationResponse[apiKey=REDACTED, uuid=%s, displayPrefix=%s, keyType=%s, expiresAt=%s]"
            .formatted(uuid, displayPrefix, keyType, expiresAt);
    }
}
