use std::path::{Path, PathBuf};
use std::process::Command;

use hpds_format::{
    CryptoKey, FormatError, decrypt_java_block, decrypt_java_block_with_aad, encrypt_java_block,
    encrypt_java_block_with_aad,
};

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

fn read(name: &str) -> Vec<u8> {
    std::fs::read(fixture(name)).unwrap()
}

#[test]
fn decrypts_blocks_written_by_java_crypto() {
    let plaintext = read("plaintext.txt");
    for (name, bits) in [("aes256", 256), ("aes192", 192), ("aes128", 128)] {
        let key = CryptoKey::load(fixture(&format!("key_{name}.txt"))).unwrap();
        assert_eq!(key.bits(), bits);
        let decrypted = decrypt_java_block(&key, &read(&format!("java_{name}.bin"))).unwrap();
        assert_eq!(decrypted, plaintext, "{name}");
    }
    let key = CryptoKey::load(fixture("key_aes256.txt")).unwrap();
    assert!(
        decrypt_java_block(&key, &read("java_aes256_empty.bin"))
            .unwrap()
            .is_empty()
    );
}

#[test]
fn key_file_is_trimmed_and_never_hex_decoded() {
    let key = CryptoKey::from_key_file_bytes(b"\t 0123456789abcdef0123456789abcdef \r\n").unwrap();
    assert_eq!(key.bits(), 256);
    let hex_as_bytes = CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap();
    let sealed = encrypt_java_block(&hex_as_bytes, b"x").unwrap();
    assert_eq!(decrypt_java_block(&key, &sealed).unwrap(), b"x");
    assert!(matches!(
        CryptoKey::from_key_file_bytes(b"0123456789abcdef0123456789abcde"),
        Err(FormatError::InvalidKeyLength(31))
    ));
    assert!(matches!(
        CryptoKey::from_key_file_bytes(b"\xc2\xa00123456789abcdef0123456789abcdef"),
        Err(FormatError::InvalidKeyLength(34))
    ));
}

#[test]
fn wrong_key_fails_authentication() {
    let wrong = CryptoKey::from_bytes(b"fedcba9876543210fedcba9876543210").unwrap();
    assert!(matches!(
        decrypt_java_block(&wrong, &read("java_aes256.bin")),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn associated_data_must_match() {
    let key = CryptoKey::from_bytes(&[7u8; 32]).unwrap();
    let sealed = encrypt_java_block_with_aad(&key, b"payload", b"\\a\\").unwrap();
    assert_eq!(
        decrypt_java_block_with_aad(&key, &sealed, b"\\a\\").unwrap(),
        b"payload"
    );
    assert!(matches!(
        decrypt_java_block_with_aad(&key, &sealed, b"\\b\\"),
        Err(FormatError::Authentication)
    ));
    assert!(matches!(
        decrypt_java_block(&key, &sealed),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn framing_matches_java_and_nonces_are_fresh() {
    let key = CryptoKey::from_bytes(&[1u8; 16]).unwrap();
    let a = encrypt_java_block(&key, b"abc").unwrap();
    let b = encrypt_java_block(&key, b"abc").unwrap();
    assert_eq!(a.len(), 4 + 12 + 3 + 16);
    assert_eq!(&a[..4], &12i32.to_be_bytes());
    assert_ne!(a[4..16], b[4..16]);
}

#[test]
fn corrupt_and_truncated_frames_are_errors() {
    let key = CryptoKey::load(fixture("key_aes256.txt")).unwrap();
    let good = read("java_aes256.bin");
    for len in 0..good.len() {
        assert!(
            decrypt_java_block(&key, &good[..len]).is_err(),
            "truncated to {len}"
        );
    }
    for i in 0..good.len() {
        let mut bad = good.clone();
        bad[i] ^= 0x01;
        assert!(decrypt_java_block(&key, &bad).is_err(), "flipped byte {i}");
    }
    let mut negative = good.clone();
    negative[..4].copy_from_slice(&(-1i32).to_be_bytes());
    assert!(matches!(
        decrypt_java_block(&key, &negative),
        Err(FormatError::Corrupt { .. })
    ));
}

/// Encrypts with Rust and decrypts with Java `Crypto.decryptData` through jshell.
/// Run with `HPDS_JSHELL` set to a JDK 25 `jshell` and `HPDS_JAVA_CLASSPATH` to the HPDS
/// common jar plus commons-io and slf4j-api.
#[test]
#[ignore = "needs a JDK and the HPDS common jar; see the doc comment"]
fn java_decrypts_rust_blocks() {
    let jshell = std::env::var("HPDS_JSHELL").expect("HPDS_JSHELL");
    let classpath = std::env::var("HPDS_JAVA_CLASSPATH").expect("HPDS_JAVA_CLASSPATH");
    let dir = std::env::temp_dir().join(format!("hpds-format-java-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let plaintext = read("plaintext.txt");
    for name in ["aes256", "aes192", "aes128"] {
        let key_file = fixture(&format!("key_{name}.txt"));
        let key = CryptoKey::load(&key_file).unwrap();
        let cipher_file = dir.join(format!("rust_{name}.bin"));
        let out_file = dir.join(format!("java_out_{name}.txt"));
        std::fs::write(&cipher_file, encrypt_java_block(&key, &plaintext).unwrap()).unwrap();
        let status = Command::new(&jshell)
            .arg("-q")
            .arg("--class-path")
            .arg(&classpath)
            .arg(format!("-R-Dkey.file={}", key_file.display()))
            .arg(format!("-R-Dcipher.file={}", cipher_file.display()))
            .arg(format!("-R-Dout.file={}", out_file.display()))
            .arg(fixture("decrypt_with_java.jsh"))
            .status()
            .unwrap();
        assert!(status.success());
        assert_eq!(std::fs::read(&out_file).unwrap(), plaintext, "{name}");
    }
    std::fs::remove_dir_all(&dir).unwrap();
}
