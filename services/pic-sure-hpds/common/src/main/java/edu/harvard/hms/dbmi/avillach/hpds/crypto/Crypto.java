package edu.harvard.hms.dbmi.avillach.hpds.crypto;


import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

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

	private static final Logger LOGGER = LoggerFactory.getLogger(Crypto.class);
	private static byte[] key;

	static {
		try {
			setKey(IOUtils.toString(new FileInputStream("/opt/local/hpds/encryption_key"), "UTF-8").trim().getBytes());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			LOGGER.debug("****CRYPTO KEY NOT FOUND, SOMEONE WILL HAVE TO UNLOCK THE SERVICE REMOTELY****");
		}
		LOGGER.debug("****LOADED CRYPTO KEY****");	
	}

	public static byte[] encryptData(byte[] plaintextBytes) {
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
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new RuntimeException("Exception while trying to encrypt data : ", e);
		}
	}

	public static byte[] decryptData(byte[] encrypted) {
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
			e.printStackTrace();
			throw new RuntimeException("Exception caught trying to decrypt data : " + e);
		}
	}

	public static void setKey(byte[] key) {
		Crypto.key = key;
	}

	public static boolean hasKey() {
		return key != null;
	}

}
