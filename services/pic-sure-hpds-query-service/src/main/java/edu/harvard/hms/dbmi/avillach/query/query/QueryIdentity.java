package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * Derives the two ids a stored query has from the user who owns it and from what the query asks for.
 *
 * <p>Both ids are RFC 4122 version 5 (name-based, SHA-1) UUIDs with the user's UUID as the namespace. The name is the 32-byte SHA-256
 * digest of the UTF-8 bytes of a canonical form of the query. The same user and the same query always give the same ids, and two users
 * never share one. The query's own {@code id} and {@code picsureId} and its {@code authorizationFilters} are in neither form, so a change
 * of consents keeps both ids.
 *
 * <p><b>The row id</b>, {@link #idFor}, hashes {@link CanonicalQuery#of(Query)}. {@code sample} and {@code expectedResultType} are part of
 * that form, so a sample and a full export, or two export formats, of one cohort get different row ids. The row id is also the key of the
 * HPDS result.
 *
 * <p><b>The general id</b>, {@link #generalIdFor}, hashes {@link CanonicalQuery#generalOf(Query)}, which leaves those two members out. It
 * names the general query: one user's query whatever its result type and whether or not it is a sample. Every row of one user that differs
 * only in {@code sample} or {@code expectedResultType} has the same general id. The row id and the general id of one query are never
 * derived from the same string, because the two forms of a query always differ.
 *
 * <p>Both forms are written under the one version in {@code CanonicalQuery.FORM_VERSION}, so a change of the rules moves both ids together.
 *
 * <p>Both ids are permanent database keys. Changing either canonical form, the digest or the namespace orphans every stored row and every
 * stored general query.
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
        return version5(userUuid, CanonicalQuery.of(query));
    }

    /**
     * Returns the id of the general query this query is a variant of for this user. Queries of one user that differ only in
     * {@code expectedResultType} or {@code sample} share it.
     *
     * @param userUuid the UUID of the user who submits the query, used as the version 5 namespace
     * @param query the query, read through {@link CanonicalQuery#generalOf(Query)}
     * @return a version 5 UUID that is the same on every call with an equal user and a query that is equivalent apart from its result type
     *         and its sample flag, and that differs from {@link #idFor} of the same user and query
     * @throws NullPointerException when the user UUID or the query is null
     */
    public static UUID generalIdFor(UUID userUuid, Query query) {
        Objects.requireNonNull(userUuid, "userUuid is null");
        return version5(userUuid, CanonicalQuery.generalOf(query));
    }

    /**
     * Returns the version 5 UUID of a canonical form in the namespace of a user.
     *
     * @param userUuid the namespace
     * @param canonicalForm the form whose SHA-256 digest over its UTF-8 bytes is the name
     * @return the version 5 UUID
     */
    private static UUID version5(UUID userUuid, String canonicalForm) {
        return UUIDv5.UUIDFromNamespaceAndBytes(userUuid, sha256(canonicalForm.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }
}
