package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * An API key as the listing and revocation endpoints describe it: everything about the key except key material. Every member is always
 * written, the optional ones as {@code null}.
 *
 * @param uuid the row identifier
 * @param displayPrefix the first characters of the key
 * @param keyType whether the key is a USER or a PLATFORM key
 * @param name the name given when the key was created, or {@code null}
 * @param email the contact email given when the key was created, or {@code null}
 * @param createdAt when the key was created
 * @param expiresAt when the key expires, or {@code null} when it never does
 * @param revokedAt when the key was revoked, or {@code null} while it is live
 * @param lastUsedAt when the key was last presented, or {@code null} when it never was
 */
@Schema(description = "An API key without its key material. Every member is always present, the optional ones as null.")
public record ApiKeyMetadata(
    @Schema(
        description = "Row identifier of the key.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The first characters of the key, for recognizing it.", example = "00000000",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String displayPrefix,
    @Schema(description = "Whether the key is a USER or a PLATFORM key.", requiredMode = Schema.RequiredMode.REQUIRED) ApiKeyType keyType,
    @Schema(
        description = "Name given when the key was created. Null when none was given.", example = "Jane Doe",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,
    @Schema(
        description = "Contact email given when the key was created. Null when none was given.", example = "researcher@example.org",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String email,
    @Schema(
        description = "When the key was created, as an ISO instant.", example = "2026-09-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant createdAt,
    @Schema(
        description = "When the key expires, as an ISO instant. Null when it never expires.", example = "2026-10-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant expiresAt,
    @Schema(
        description = "When the key was revoked, as an ISO instant. Null while the key is live.", example = "2026-09-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant revokedAt,
    @Schema(
        description = "When the key was last presented, as an ISO instant. Null when it never was.", example = "2026-09-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant lastUsedAt
) {

    /**
     * Copies a stored key into its metadata shape.
     *
     * @param apiKey the stored key
     * @return the metadata record
     */
    public static ApiKeyMetadata from(ApiKey apiKey) {
        return new ApiKeyMetadata(
            apiKey.getUuid(), apiKey.getDisplayPrefix(), apiKey.getKeyType(), apiKey.getName(), apiKey.getEmail(), apiKey.getCreatedAt(),
            apiKey.getExpiresAt(), apiKey.getRevokedAt(), apiKey.getLastUsedAt()
        );
    }
}
