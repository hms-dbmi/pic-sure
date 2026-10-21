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

#[test]
fn an_unwritable_log_directory_refuses_startup() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("occupied");
    std::fs::write(&file, b"").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-rs"))
        .env("HPDS_CONSENT_CONTRACT", "main")
        .env("HPDS_LOG_FORMAT", "logback")
        .env("HPDS_LOG_DIRECTORY", file.join("logs"))
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("cannot write the log file in"), "{stdout}");
    assert!(stdout.contains("ERROR --- [hpds]"), "{stdout}");
}

#[derive(Clone, Default)]
struct Capture(std::sync::Arc<std::sync::Mutex<Vec<u8>>>);

impl std::io::Write for Capture {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for Capture {
    type Writer = Capture;
    fn make_writer(&'a self) -> Capture {
        self.clone()
    }
}

#[test]
fn a_panic_is_logged_in_the_active_format() {
    use tracing_subscriber::layer::SubscriberExt;
    let capture = Capture::default();
    let format = hpds_rs::LogFormat::Logback {
        name: "hpds".to_owned(),
    };
    let subscriber =
        tracing_subscriber::registry().with(hpds_rs::format_layer(&format, capture.clone(), None));
    hpds_rs::logging::install_panic_hook();
    let caught = tracing::subscriber::with_default(subscriber, || {
        std::panic::catch_unwind(|| panic!("index out of bounds"))
    });
    let _ = std::panic::take_hook();
    assert!(caught.is_err());
    let output = String::from_utf8(capture.0.lock().unwrap().clone()).unwrap();
    assert!(output.contains("ERROR --- [hpds] ["), "{output}");
    assert!(
        output.contains("hpds_rs.panic : panic: index out of bounds at "),
        "{output}"
    );
}
