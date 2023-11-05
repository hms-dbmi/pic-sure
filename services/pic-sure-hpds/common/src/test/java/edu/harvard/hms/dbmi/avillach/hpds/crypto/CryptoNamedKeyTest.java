package edu.harvard.hms.dbmi.avillach.hpds.crypto;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.crypto.AEADBadTagException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.event.annotation.BeforeTestClass;

import static org.junit.jupiter.api.Assertions.*;

@Disabled // We should rewrite the crypto class to make it more testable, these tests don't work on certain JDKs
public class CryptoNamedKeyTest {

	private static final String TEST_NAMED_ENCRYPTIOON_KEY_PATH = "src/test/resources/test_named_encryption_key";

	String TEST_MESSAGE = "This is a test.";

	String TEST_NAMED_KEY = "TEST_NAMED_KEY";

	@BeforeTestClass
	public static void overrideDefaultKeyLocation() throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
		Field field = Crypto.class.getDeclaredField("DEFAULT_ENCRYPTION_KEY_PATH");
		field.setAccessible(true);
		Field modifiersField = Field.class.getDeclaredField("modifiers");
		modifiersField.setAccessible(true);
		modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
		field.set(Crypto.class, new File("src/test/resources/test_encryption_key").getAbsolutePath());
		Crypto.loadDefaultKey();
	}

	@Test
	public void testNamedKeyEncryptDecrypt() {
		Crypto.loadKey("TEST_NAMED_KEY", TEST_NAMED_ENCRYPTIOON_KEY_PATH);
		byte[] ciphertext = Crypto.encryptData(TEST_MESSAGE.getBytes());
		assertTrue(!new String(ciphertext).contentEquals(TEST_MESSAGE));
		String plaintext = new String(Crypto.decryptData(ciphertext));
		assertEquals(plaintext, TEST_MESSAGE);
	}

	@Test
	public void testNamedKeyEncryptNotUsingDefaultKey() {
		assertThrows(AEADBadTagException.class, () -> {
			Crypto.loadKey(TEST_NAMED_KEY, TEST_NAMED_ENCRYPTIOON_KEY_PATH);
			byte[] ciphertext = Crypto.encryptData(TEST_NAMED_KEY, TEST_MESSAGE.getBytes());
			assertFalse(new String(ciphertext).contentEquals(TEST_MESSAGE));
			Crypto.decryptData(ciphertext);
		});
	}

	@Test
	public void testNamedKeyDecryptNotUsingDefaultKey() {
		assertThrows(AEADBadTagException.class, () -> {
			Crypto.loadKey(TEST_NAMED_KEY, TEST_NAMED_ENCRYPTIOON_KEY_PATH);
			byte[] ciphertext = Crypto.encryptData(TEST_MESSAGE.getBytes());
			assertTrue(!new String(ciphertext).contentEquals(TEST_MESSAGE));
			Crypto.decryptData(TEST_NAMED_KEY, ciphertext);
		});
	}
}
