package edu.harvard.dbmi.avillach.util;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public class UUIDv5 {
	private static final Charset UTF8 = Charset.forName("UTF-8");
	
	///random UUID for namespace;  do NOT change this!
	public static final UUID NAMESPACE_AVL = UUID.fromString("a4f66130-cb71-4d7f-a58c-9f1831ebfb8e");
	
	/**
	 * Generate a Type 5 UUID based on a single sting input and a default namespace.
	 * @param namespace
	 * @param source
	 * @return
	 */
	public static UUID UUIDFromString(String source) {
        return UUIDFromNamespaceAndBytes(NAMESPACE_AVL, Objects.requireNonNull(source, "source == null").getBytes(UTF8));
    }
	
	/**
	 * Generates a Type 5 UUID based on the provided namespace ID and a byte array.
	 * @param namespace
	 * @param source
	 * @return
	 */
	public static UUID UUIDFromNamespaceAndBytes(UUID namespace, byte[] source) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException nsae) {
            throw new InternalError("SHA-1 not supported");
        }
        md.update(toBytes(Objects.requireNonNull(namespace, "namespace is null")));
        md.update(Objects.requireNonNull(source, "name is null"));
        byte[] sha1Bytes = md.digest();
        sha1Bytes[6] &= 0x0f;  /* clear version        */
        sha1Bytes[6] |= 0x50;  /* set to version 5     */
        sha1Bytes[8] &= 0x3f;  /* clear variant        */
        sha1Bytes[8] |= 0x80;  /* set to IETF variant  */
        return fromBytes(sha1Bytes);
    }

    private static UUID fromBytes(byte[] data) {
        // Based on the private UUID(bytes[]) constructor
        long msb = 0;
        long lsb = 0;
        assert data.length >= 16;
        for (int i = 0; i < 8; i++)
            msb = (msb << 8) | (data[i] & 0xff);
        for (int i = 8; i < 16; i++)
            lsb = (lsb << 8) | (data[i] & 0xff);
        return new UUID(msb, lsb);
    }
	
    private static byte[] toBytes(UUID uuid) {
        // inverted logic of fromBytes()
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++)
            out[i] = (byte) ((msb >> ((7 - i) * 8)) & 0xff);
        for (int i = 8; i < 16; i++)
            out[i] = (byte) ((lsb >> ((15 - i) * 8)) & 0xff);
        return out;
    }
}
