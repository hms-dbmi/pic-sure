package edu.harvard.hms.dbmi.avillach.hpds.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.crypto.AEADBadTagException;

import org.junit.BeforeClass;
import org.junit.Test;

public class CryptoNamedKeyTest {

	private static final String TEST_NAMED_ENCRYPTIOON_KEY_PATH = "src/test/resources/test_named_encryption_key";

	String TEST_MESSAGE = "This is a test.";

	String TEST_NAMED_KEY = "TEST_NAMED_KEY";

	@BeforeClass
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
		Crypto.loadKey(TEST_NAMED_KEY, TEST_NAMED_ENCRYPTIOON_KEY_PATH);
		byte[] ciphertext = Crypto.encryptData(TEST_NAMED_KEY, TEST_MESSAGE.getBytes());
		assertTrue(!new String(ciphertext).contentEquals(TEST_MESSAGE));
		try{
			Crypto.decryptData(ciphertext);
		}catch(RuntimeException e) {
			assertEquals(e.getCause().getClass(), AEADBadTagException.class);
			return;
		}
		fail("Expected AEADBadTagException to be thrown");
	}

	@Test
	public void testNamedKeyDecryptNotUsingDefaultKey() {
		Crypto.loadKey(TEST_NAMED_KEY, TEST_NAMED_ENCRYPTIOON_KEY_PATH);
		byte[] ciphertext = Crypto.encryptData(TEST_MESSAGE.getBytes());
		assertTrue(!new String(ciphertext).contentEquals(TEST_MESSAGE));
		try{
			Crypto.decryptData(TEST_NAMED_KEY, ciphertext);
		}catch(RuntimeException e) {
			assertEquals(e.getCause().getClass(), AEADBadTagException.class);
			return;
		}
		fail("Expected AEADBadTagException to be thrown");
	}
}
