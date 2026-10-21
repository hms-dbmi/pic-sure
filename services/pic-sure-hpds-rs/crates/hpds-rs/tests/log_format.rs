use std::process::Command;

#[test]
fn an_unknown_log_format_refuses_startup_naming_the_variable() {
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-rs"))
        .env("HPDS_CONSENT_CONTRACT", "main")
        .env("HPDS_LOG_FORMAT", "json")
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("HPDS_LOG_FORMAT must be default or logback"),
        "{stdout}"
    );
}
