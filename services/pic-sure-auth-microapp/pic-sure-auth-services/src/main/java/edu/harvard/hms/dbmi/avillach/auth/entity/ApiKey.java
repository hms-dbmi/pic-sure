package edu.harvard.hms.dbmi.avillach.auth.entity;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

@Entity(name = "api_key")
public class ApiKey extends BaseEntity {

    @Column(name = "key_hash", unique = true, nullable = false, length = 64)
    private String keyHash;

    @Column(name = "hash_scheme", nullable = false, length = 16)
    private String hashScheme;

    @Column(name = "display_prefix", nullable = false, length = 8)
    private String displayPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, length = 16)
    private ApiKeyType keyType;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // null means the key never expires (explicit opt-in, PLATFORM keys only)
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public String getKeyHash() {
        return keyHash;
    }

    public ApiKey setKeyHash(String keyHash) {
        this.keyHash = keyHash;
        return this;
    }

    public String getHashScheme() {
        return hashScheme;
    }

    public ApiKey setHashScheme(String hashScheme) {
        this.hashScheme = hashScheme;
        return this;
    }

    public String getDisplayPrefix() {
        return displayPrefix;
    }

    public ApiKey setDisplayPrefix(String displayPrefix) {
        this.displayPrefix = displayPrefix;
        return this;
    }

    public ApiKeyType getKeyType() {
        return keyType;
    }

    public ApiKey setKeyType(ApiKeyType keyType) {
        this.keyType = keyType;
        return this;
    }

    public String getName() {
        return name;
    }

    public ApiKey setName(String name) {
        this.name = name;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public ApiKey setEmail(String email) {
        this.email = email;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ApiKey setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ApiKey setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public ApiKey setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
        return this;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public ApiKey setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
        return this;
    }
}
