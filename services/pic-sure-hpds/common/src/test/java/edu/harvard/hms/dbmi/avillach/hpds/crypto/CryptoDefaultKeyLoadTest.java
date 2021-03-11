package edu.harvard.hms.dbmi.avillach.hpds.crypto;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

public class CryptoDefaultKeyLoadTest {
	
	@Test
	public void testCryptoLoadsKey() throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
		Field field = Crypto.class.getDeclaredField("DEFAULT_ENCRYPTION_KEY_PATH");
		field.setAccessible(true);
		Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
		field.set(Crypto.class, new File("src/test/resources/test_encryption_key").getAbsolutePath());
        Crypto.loadDefaultKey();
        assertTrue(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME));
	}
}
