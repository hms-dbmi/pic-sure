use std::path::{Path, PathBuf};

use hpds_format::{
    CryptoKey, FormatError, decrypt_java_block, encrypt_java_block, open_framed, seal_framed,
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
    let sealed = seal_framed(&key, b"payload", b"\\a\\").unwrap();
    assert_eq!(open_framed(&key, &sealed, b"\\a\\").unwrap(), b"payload");
    assert!(matches!(
        open_framed(&key, &sealed, b"\\b\\"),
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

/// The Rust-sealed fixture that `CryptoRustFixtureTest` in the HPDS common module decrypts
/// with Java `Crypto.decryptData`. When the Java module is present (not under `bin/cargo`,
/// which mounts only this workspace), its copy must be byte-identical to this one.
#[test]
fn rust_sealed_fixture_for_java_opens() {
    let key = CryptoKey::load(fixture("key_aes256.txt")).unwrap();
    assert_eq!(
        decrypt_java_block(&key, &read("rust_aes256.bin")).unwrap(),
        read("plaintext.txt")
    );
    let java_copy = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../pic-sure-hpds/common/src/test/resources/crypto/rust_aes256.bin");
    if let Ok(copy) = std::fs::read(java_copy) {
        assert_eq!(copy, read("rust_aes256.bin"));
    }
}
