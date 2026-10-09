package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * AES-GCM decryption for the layout store, in the framing {@code Crypto.encryptData} writes ({@code ivLength} as a big-endian i32, always
 * 12, then the IV, then ciphertext followed by a 128-bit tag), with the associated data the layout format binds. The key is the UTF-8 bytes
 * of the trimmed key file, never hex-decoded, as {@code Crypto.loadKey} reads it.
 */
public final class LayoutCipher {

    private static final int IV_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    private LayoutCipher(byte[] key) {
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new LayoutFormatException("encryption key is " + key.length + " bytes; expected 16, 24 or 32");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /**
     * Reads a key file with the rules of {@code Crypto.loadKey}.
     *
     * @param keyFile the key file, normally {@code /opt/local/hpds/encryption_key}
     * @return a cipher holding that key
     * @throws LayoutFormatException when the file cannot be read or the trimmed key is not 16, 24 or 32 bytes
     */
    public static LayoutCipher fromKeyFile(Path keyFile) {
        try {
            return new LayoutCipher(Files.readString(keyFile, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LayoutFormatException("cannot read encryption key " + keyFile, e);
        }
    }

    /**
     * Decrypts one framed message.
     *
     * @param framed the stored bytes
     * @param associatedData the associated data the message was sealed with
     * @param what a name for the message, used in error messages
     * @return the plaintext
     * @throws LayoutFormatException when the framing is invalid, or authentication fails because the key, the associated data or the bytes
     *         differ from what was sealed
     */
    public byte[] open(byte[] framed, byte[] associatedData, String what) {
        if (framed.length < 4 + IV_LENGTH + TAG_BITS / 8) {
            throw new LayoutFormatException(what + " is too short to be encrypted");
        }
        int ivLength = ByteBuffer.wrap(framed, 0, 4).getInt();
        if (ivLength != IV_LENGTH) {
            throw new LayoutFormatException(what + " declares IV length " + ivLength + ", expected " + IV_LENGTH);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, framed, 4, IV_LENGTH));
            cipher.updateAAD(associatedData);
            int start = 4 + IV_LENGTH;
            return cipher.doFinal(framed, start, framed.length - start);
        } catch (GeneralSecurityException e) {
            throw new LayoutFormatException(what + " failed authentication", e);
        }
    }
}
