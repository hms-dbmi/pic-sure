package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyMetadata;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Generates, verifies, and manages API keys for open-access requests. Only a hash of each key is stored; the plaintext exists solely in the
 * {@link ApiKeyCreationResponse} returned at creation.
 */
@Service
public class ApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    public static final String KEY_PREFIX = "picsure_";
    public static final String USER_KEY_PREFIX = KEY_PREFIX + "u_";
    public static final String PLATFORM_KEY_PREFIX = KEY_PREFIX + "p_";
    public static final String SCHEME_SHA256 = "SHA256";
    public static final String SCHEME_HMAC_SHA256 = "HMAC_SHA256";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    // 43 base62 characters carry just under 256 bits of entropy
    private static final int KEY_BODY_LENGTH = 43;
    // 62^6 > 2^32, so 6 base62 characters always fit a CRC32
    private static final int CHECKSUM_LENGTH = 6;
    private static final int DISPLAY_PREFIX_LENGTH = 8;
    private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository apiKeyRepository;
    private final String pepper;
    private final List<String> previousPeppers;
    private final long userKeyTtlDays;
    private final long platformKeyTtlDays;

    @Autowired
    public ApiKeyService(
        ApiKeyRepository apiKeyRepository, @Value("${api.key.pepper}") String pepper,
        @Value("${api.key.pepper.previous}") String previousPeppers, @Value("${api.key.user.ttl.days}") long userKeyTtlDays,
        @Value("${api.key.platform.ttl.days}") long platformKeyTtlDays
    ) {
        if (userKeyTtlDays <= 0) {
            throw new IllegalStateException("api.key.user.ttl.days must be positive, was " + userKeyTtlDays);
        }
        if (platformKeyTtlDays <= 0) {
            throw new IllegalStateException("api.key.platform.ttl.days must be positive, was " + platformKeyTtlDays);
        }
        this.apiKeyRepository = apiKeyRepository;
        this.pepper = pepper;
        this.previousPeppers = previousPeppers == null ? List.of()
            : Arrays.stream(previousPeppers.split(",")).map(String::trim).filter(ApiKeyService::isSet).toList();
        this.userKeyTtlDays = userKeyTtlDays;
        this.platformKeyTtlDays = platformKeyTtlDays;
    }

    public ApiKeyCreationResponse generateUserKey(String name, String email) {
        return generate(ApiKeyType.USER, name, email, null, false);
    }

    /**
     * @param expiresAt explicit expiry, or null to apply the platform TTL default
     * @param neverExpires mint a non-expiring key; mutually exclusive with {@code expiresAt}
     */
    public ApiKeyCreationResponse generatePlatformKey(String name, String email, Instant expiresAt, boolean neverExpires) {
        return generate(ApiKeyType.PLATFORM, name, email, expiresAt, neverExpires);
    }

    private ApiKeyCreationResponse generate(ApiKeyType keyType, String name, String email, Instant expiresAt, boolean neverExpires) {
        Instant createdAt = Instant.now();
        if (neverExpires && expiresAt != null) {
            throw new IllegalArgumentException("neverExpires and expiresAt are mutually exclusive");
        }
        if (!neverExpires) {
            if (expiresAt == null) {
                long ttlDays = keyType == ApiKeyType.PLATFORM ? platformKeyTtlDays : userKeyTtlDays;
                expiresAt = createdAt.plus(ttlDays, ChronoUnit.DAYS);
            }
            if (!expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("API key expiration must be in the future");
            }
        }
        String body = randomBase62(KEY_BODY_LENGTH);
        String prefixedBody = typedPrefix(keyType) + body;
        String plaintext = prefixedBody + checksum(prefixedBody);
        ApiKey apiKey = new ApiKey().setKeyHash(hashWithCurrentScheme(plaintext)).setHashScheme(currentScheme())
            .setDisplayPrefix(body.substring(0, DISPLAY_PREFIX_LENGTH)).setKeyType(keyType).setName(name).setEmail(email)
            .setCreatedAt(createdAt).setExpiresAt(expiresAt);
        apiKey = apiKeyRepository.save(apiKey);
        logger.info("Generated {} API key {} with display prefix {}", keyType, apiKey.getUuid(), apiKey.getDisplayPrefix());
        return new ApiKeyCreationResponse(plaintext, apiKey.getUuid(), apiKey.getDisplayPrefix(), keyType, expiresAt);
    }

    /**
     * Returns the matching key only when it is currently valid: known, unrevoked, unexpired.
     */
    public Optional<ApiKey> verifyKey(String plaintext) {
        if (plaintext == null || !hasTypedPrefix(plaintext) || !hasValidChecksum(plaintext)) {
            return Optional.empty();
        }

        Optional<ApiKey> found = findUnderAnyActiveScheme(plaintext);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ApiKey apiKey = found.get();
        // only possible if key_type was altered after minting; fail closed
        if (!plaintext.startsWith(typedPrefix(apiKey.getKeyType()))) {
            logger.warn(
                "Rejected API key with display prefix {}: key prefix does not match stored key type {}", apiKey.getDisplayPrefix(),
                apiKey.getKeyType()
            );
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (apiKey.getRevokedAt() != null || isExpired(apiKey, now)) {
            logger.info(
                "Rejected {} API key with display prefix {}: {}", apiKey.getKeyType(), apiKey.getDisplayPrefix(),
                apiKey.getRevokedAt() != null ? "revoked" : "expired"
            );
            return Optional.empty();
        }

        touchLastUsed(apiKey, now);
        return Optional.of(apiKey);
    }

    public ApiKeyPage listKeys(int page, int size, ApiKeyType keyType) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "uuid"));
        Page<ApiKey> keys = keyType == null ? apiKeyRepository.findAll(pageRequest) : apiKeyRepository.findByKeyType(keyType, pageRequest);
        return new ApiKeyPage(keys.getContent().stream().map(ApiKeyMetadata::from).toList(), keys.getTotalElements(), page, size);
    }

    public Optional<ApiKeyMetadata> revokeKey(UUID uuid) {
        return apiKeyRepository.findById(uuid).map(apiKey -> {
            if (apiKey.getRevokedAt() == null) {
                apiKey.setRevokedAt(Instant.now());
                apiKey = apiKeyRepository.save(apiKey);
                logger.info("Revoked API key {} with display prefix {}", apiKey.getUuid(), apiKey.getDisplayPrefix());
            }
            return ApiKeyMetadata.from(apiKey);
        });
    }

    /**
     * Tries the current pepper, then each previous pepper (covering rotation, oldest keys last), then the unpeppered scheme (covering keys
     * minted before a pepper was configured). Unset peppers are skipped, so HMAC-backed keys still verify while the current pepper is
     * accidentally omitted, as long as it appears in the previous-pepper list.
     */
    private Optional<ApiKey> findUnderAnyActiveScheme(String plaintext) {
        List<String> activePeppers = new ArrayList<>();
        if (hasPepper()) {
            activePeppers.add(pepper);
        }
        activePeppers.addAll(previousPeppers);
        for (String activePepper : activePeppers) {
            Optional<ApiKey> found = apiKeyRepository.findByKeyHash(hmacSha256(activePepper, plaintext))
                .filter(key -> SCHEME_HMAC_SHA256.equals(key.getHashScheme()));
            if (found.isPresent()) {
                return found;
            }
        }
        return apiKeyRepository.findByKeyHash(sha256(plaintext)).filter(key -> SCHEME_SHA256.equals(key.getHashScheme()));
    }

    // last_used_at is usage telemetry, not an audit trail; throttling avoids a DB write per request
    // and a failed write must not reject an otherwise valid key. The in-memory check is only a fast
    // path — the cutoff in the UPDATE is what makes the throttle hold under concurrency
    private void touchLastUsed(ApiKey apiKey, Instant now) {
        Instant cutoff = now.minus(LAST_USED_WRITE_INTERVAL);
        if (apiKey.getLastUsedAt() == null || apiKey.getLastUsedAt().isBefore(cutoff)) {
            try {
                apiKeyRepository.touchLastUsed(apiKey.getUuid(), now, cutoff);
            } catch (RuntimeException e) {
                logger.warn("Failed to update last_used_at for API key with display prefix {}", apiKey.getDisplayPrefix(), e);
            }
        }
    }

    // null expiry means "never expires", an opt-in that exists only for PLATFORM keys. The DB CHECK
    // enforces that too, but parallel deployment schemas are written separately — fail closed here
    // in case one of them misses the constraint
    private static boolean isExpired(ApiKey apiKey, Instant now) {
        if (apiKey.getExpiresAt() == null) {
            return apiKey.getKeyType() != ApiKeyType.PLATFORM;
        }
        return !now.isBefore(apiKey.getExpiresAt());
    }

    private static String typedPrefix(ApiKeyType keyType) {
        return keyType == ApiKeyType.PLATFORM ? PLATFORM_KEY_PREFIX : USER_KEY_PREFIX;
    }

    private static boolean hasTypedPrefix(String plaintext) {
        return plaintext.startsWith(USER_KEY_PREFIX) || plaintext.startsWith(PLATFORM_KEY_PREFIX);
    }

    // not a security control: a valid checksum is trivially forgeable
    private static boolean hasValidChecksum(String plaintext) {
        // both typed prefixes have the same length
        if (plaintext.length() != USER_KEY_PREFIX.length() + KEY_BODY_LENGTH + CHECKSUM_LENGTH) {
            return false;
        }
        int checksumStart = plaintext.length() - CHECKSUM_LENGTH;
        return checksum(plaintext.substring(0, checksumStart)).equals(plaintext.substring(checksumStart));
    }

    private static String checksum(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        long remaining = crc.getValue();
        StringBuilder encoded = new StringBuilder(CHECKSUM_LENGTH);
        for (int i = 0; i < CHECKSUM_LENGTH; i++) {
            encoded.append(BASE62_ALPHABET.charAt((int) (remaining % 62)));
            remaining /= 62;
        }
        return encoded.reverse().toString();
    }

    private boolean hasPepper() {
        return isSet(pepper);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private String currentScheme() {
        return hasPepper() ? SCHEME_HMAC_SHA256 : SCHEME_SHA256;
    }

    private String hashWithCurrentScheme(String plaintext) {
        return hasPepper() ? hmacSha256(pepper, plaintext) : sha256(plaintext);
    }

    private static String sha256(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256(String pepper, String plaintext) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String randomBase62(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE62_ALPHABET.charAt(SECURE_RANDOM.nextInt(BASE62_ALPHABET.length())));
        }
        return builder.toString();
    }
}
