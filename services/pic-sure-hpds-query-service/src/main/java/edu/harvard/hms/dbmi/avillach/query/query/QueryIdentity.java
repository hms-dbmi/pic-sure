package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * Derives the id of a query row from the user who owns it and from what the query asks for.
 *
 * <p>The id is an RFC 4122 version 5 (name-based, SHA-1) UUID. The namespace is the user's UUID. The name is the 32-byte SHA-256 digest of
 * the UTF-8 bytes of {@link CanonicalQuery#of(Query)}. The same user and the same query always give the same id, and two users never share
 * one. The query's own {@code id} and {@code picsureId} and its {@code authorizationFilters} are not part of the canonical form, so a
 * change of consents keeps the id. {@code sample} and {@code expectedResultType} are part of it, so a sample and a full export, or two
 * export formats, of one cohort get different ids.
 *
 * <p>The id is a permanent database key and the key of the HPDS result. Changing the canonical form, the digest or the namespace orphans
 * every stored row.
 */
public final class QueryIdentity {

    private QueryIdentity() {}

    /**
     * Returns the id of the row that holds this query for this user.
     *
     * @param userUuid the UUID of the user who submits the query, used as the version 5 namespace
     * @param query the query, read through {@link CanonicalQuery#of(Query)}
     * @return a version 5 UUID that is the same on every call with an equal user and an equivalent query
     * @throws NullPointerException when the user UUID or the query is null
     */
    public static UUID idFor(UUID userUuid, Query query) {
        Objects.requireNonNull(userUuid, "userUuid is null");
        byte[] canonical = CanonicalQuery.of(query).getBytes(StandardCharsets.UTF_8);
        return UUIDv5.UUIDFromNamespaceAndBytes(userUuid, sha256(canonical));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }
}
