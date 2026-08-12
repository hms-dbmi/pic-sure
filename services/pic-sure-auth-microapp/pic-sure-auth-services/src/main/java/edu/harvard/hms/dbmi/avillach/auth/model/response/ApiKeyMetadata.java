package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyMetadata(
    UUID uuid, String displayPrefix, ApiKeyType keyType, String name, String email, Instant createdAt, Instant expiresAt, Instant revokedAt,
    Instant lastUsedAt
) {

    public static ApiKeyMetadata from(ApiKey apiKey) {
        return new ApiKeyMetadata(
            apiKey.getUuid(), apiKey.getDisplayPrefix(), apiKey.getKeyType(), apiKey.getName(), apiKey.getEmail(), apiKey.getCreatedAt(),
            apiKey.getExpiresAt(), apiKey.getRevokedAt(), apiKey.getLastUsedAt()
        );
    }
}
