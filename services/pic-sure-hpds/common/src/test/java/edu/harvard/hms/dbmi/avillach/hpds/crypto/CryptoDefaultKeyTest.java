package edu.harvard.hms.dbmi.avillach.hpds.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.BeforeClass;
import org.junit.Test;

public class CryptoDefaultKeyTest {
	
	String TEST_MESSAGE = "This is a test.";
	
	@BeforeClass
	public static void overrideDefaultKeyLocation() throws IllegalArgumentException, IllegalAccessException {
	}
	
	@Test
	public void testEncryptDecrypt() throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
		Field field = Crypto.class.getDeclaredField("DEFAULT_ENCRYPTION_KEY_PATH");
		field.setAccessible(true);
		Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
		field.set(Crypto.class, new File("src/test/resources/test_encryption_key").getAbsolutePath());
		Crypto.loadDefaultKey();
		byte[] ciphertext = Crypto.encryptData(TEST_MESSAGE.getBytes());
		assertTrue(!new String(ciphertext).contentEquals(TEST_MESSAGE));
		String plaintext = new String(Crypto.decryptData(ciphertext));
		assertEquals(plaintext, TEST_MESSAGE);
	}
	
}
