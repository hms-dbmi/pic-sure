package edu.harvard.hms.dbmi.avillach.hpds.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that {@link Crypto#decryptData(String, byte[])} opens a block sealed by the Rust {@code hpds-format} crate with no associated
 * data. The fixture is a copy of {@code services/pic-sure-hpds-rs/crates/hpds-format/tests/fixtures/rust_aes256.bin}, and the Rust test
 * {@code rust_sealed_fixture_for_java_opens} fails if the two copies differ.
 */
public class CryptoRustFixtureTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/crypto");

    private static final String KEY_NAME = "RUST_FIXTURE_KEY";

    @Test
    public void decryptsBlockSealedByRust() throws IOException {
        Crypto.loadKey(KEY_NAME, FIXTURES.resolve("key_aes256.txt").toString());
        byte[] sealed = Files.readAllBytes(FIXTURES.resolve("rust_aes256.bin"));
        byte[] expected = Files.readAllBytes(FIXTURES.resolve("plaintext.txt"));

        assertArrayEquals(expected, Crypto.decryptData(KEY_NAME, sealed));
    }

    @Test
    public void rejectsTamperedRustBlock() throws IOException {
        Crypto.loadKey(KEY_NAME, FIXTURES.resolve("key_aes256.txt").toString());
        byte[] sealed = Files.readAllBytes(FIXTURES.resolve("rust_aes256.bin"));
        sealed[sealed.length - 1] ^= 1;

        assertThrows(RuntimeException.class, () -> Crypto.decryptData(KEY_NAME, sealed));
    }
}
