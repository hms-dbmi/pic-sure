package edu.harvard.hms.dbmi.avillach.hpds.crypto;


import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Crypto {

	public static final String DEFAULT_KEY_NAME = "DEFAULT";

	// This needs to be set in a static initializer block to be overridable in tests.
	private static final String DEFAULT_ENCRYPTION_KEY_PATH;
	static{
		DEFAULT_ENCRYPTION_KEY_PATH = "/opt/local/hpds/encryption_key";
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Crypto.class);

	private static final HashMap<String, byte[]> keys = new HashMap<String, byte[]>();

	public static void loadDefaultKey() {
		loadKey(DEFAULT_KEY_NAME, DEFAULT_ENCRYPTION_KEY_PATH);
	}

	public static void loadKey(String keyName, String filePath) {
		try {
			setKey(keyName, IOUtils.toString(new FileInputStream(filePath), Charset.forName("UTF-8")).trim().getBytes());
			LOGGER.info("****LOADED CRYPTO KEY****");	
		} catch (IOException e) {
			LOGGER.error("****CRYPTO KEY NOT FOUND****", e);
		}
	}

	public static byte[] encryptData(byte[] plaintextBytes) {
		return encryptData(DEFAULT_KEY_NAME, plaintextBytes);
	}
	
	public static byte[] encryptData(String keyName, byte[] plaintextBytes) {
		byte[] key = keys.get(keyName);
		SecureRandom secureRandom = new SecureRandom();
		SecretKey secretKey = new SecretKeySpec(key, "AES");
		byte[] iv = new byte[12]; //NEVER REUSE THIS IV WITH SAME KEY
		secureRandom.nextBytes(iv);
		byte[] cipherText;
		Cipher cipher;
		try {
			cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv); //128 bit auth tag length
			cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
			cipherText = new byte[cipher.getOutputSize(plaintextBytes.length)];
			cipher.doFinal(plaintextBytes, 0, plaintextBytes.length, cipherText, 0);
			LOGGER.debug("Length of cipherText : " + cipherText.length);
			ByteBuffer byteBuffer = ByteBuffer.allocate(4 + iv.length + cipherText.length);
			byteBuffer.putInt(iv.length);
			byteBuffer.put(iv);
			byteBuffer.put(cipherText);
			byte[] cipherMessage = byteBuffer.array();
			return cipherMessage;
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | ShortBufferException | IllegalBlockSizeException | BadPaddingException e) {
			throw new RuntimeException("Exception while trying to encrypt data : ", e);
		}
	}

	public static byte[] decryptData(byte[] encrypted) {
		return decryptData(DEFAULT_KEY_NAME, encrypted);
	}

	public static byte[] decryptData(String keyName, byte[] encrypted) {
		byte[] key = keys.get(keyName);
		ByteBuffer byteBuffer = ByteBuffer.wrap(encrypted);
		int ivLength = byteBuffer.getInt();
		byte[] iv = new byte[ivLength];
		byteBuffer.get(iv);
		byte[] cipherText = new byte[byteBuffer.remaining()];
		byteBuffer.get(cipherText);
		Cipher cipher;
		try {
			cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
			return cipher.doFinal(cipherText);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
			throw new RuntimeException("Exception caught trying to decrypt data : " + e, e);
		}
	}

	private static void setKey(String keyName, byte[] key) {
		keys.put(keyName, key);
	}

	public static boolean hasKey(String keyName) {
		return keys.containsKey(keyName);
	}

}
