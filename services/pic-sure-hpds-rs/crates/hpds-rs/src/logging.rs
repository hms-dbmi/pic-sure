//! Log line formats: the default `tracing` format and Java HPDS's `log.pattern` layout.

use std::fmt::{self, Write as _};
use std::time::{SystemTime, UNIX_EPOCH};

use tracing::field::{Field, Visit};
use tracing::{Event, Subscriber};
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::{self as sub_fmt, FmtContext, FormatEvent, FormatFields, MakeWriter};
use tracing_subscriber::layer::Layer;
use tracing_subscriber::registry::LookupSpan;

/// Logger name written into the bracketed field when `HPDS_LOG_NAME` is not set.
pub const DEFAULT_LOG_NAME: &str = "hpds";

const LOGGER_NAME_WIDTH: usize = 36;

/// How a log line is laid out.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum LogFormat {
    /// The `tracing_subscriber` default line.
    #[default]
    Default,
    /// Java HPDS's `log.pattern`:
    /// `%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z', UTC} %-5level --- [name] [%thread] %logger{36} : %msg%n`.
    Logback {
        /// The service tag in the first bracketed field.
        name: String,
    },
}

impl LogFormat {
    /// Reads `HPDS_LOG_FORMAT` (`default` or `logback`, ignoring case) and `HPDS_LOG_NAME`.
    ///
    /// # Errors
    /// A message naming `HPDS_LOG_FORMAT` and both accepted values when the value is neither,
    /// or naming `HPDS_LOG_NAME` when it holds a control character.
    pub fn from_values(format: Option<&str>, name: Option<&str>) -> Result<Self, String> {
        let name = match name.map(str::trim).filter(|name| !name.is_empty()) {
            Some(name) if name.chars().any(char::is_control) => {
                return Err("HPDS_LOG_NAME must not contain control characters".to_owned());
            }
            Some(name) => name.to_owned(),
            None => DEFAULT_LOG_NAME.to_owned(),
        };
        match format.map(str::trim) {
            None | Some("") => Ok(LogFormat::Default),
            Some(value) if value.eq_ignore_ascii_case("default") => Ok(LogFormat::Default),
            Some(value) if value.eq_ignore_ascii_case("logback") => Ok(LogFormat::Logback { name }),
            Some(value) => Err(format!(
                "HPDS_LOG_FORMAT must be default or logback, not {value:?}"
            )),
        }
    }
}

/// Builds a layer writing events to `writer` in `format`. `ansi` overrides the default
/// colouring of the default format and is ignored by the logback format.
pub fn format_layer<S, W>(
    format: &LogFormat,
    writer: W,
    ansi: Option<bool>,
) -> Box<dyn Layer<S> + Send + Sync + 'static>
where
    S: Subscriber + for<'a> LookupSpan<'a>,
    W: for<'a> MakeWriter<'a> + Send + Sync + 'static,
{
    match format {
        LogFormat::Default => {
            let layer = sub_fmt::layer().with_writer(writer);
            match ansi {
                Some(ansi) => Box::new(layer.with_ansi(ansi)),
                None => Box::new(layer),
            }
        }
        LogFormat::Logback { name } => Box::new(
            sub_fmt::layer()
                .with_ansi(false)
                .with_writer(writer)
                .event_format(LogbackFormat { name: name.clone() }),
        ),
    }
}

/// Formats an event as one line of Java HPDS's `log.pattern`.
#[derive(Debug, Clone)]
pub struct LogbackFormat {
    name: String,
}

impl<S, N> FormatEvent<S, N> for LogbackFormat
where
    S: Subscriber + for<'a> LookupSpan<'a>,
    N: for<'a> FormatFields<'a> + 'static,
{
    fn format_event(
        &self,
        _ctx: &FmtContext<'_, S, N>,
        mut writer: Writer<'_>,
        event: &Event<'_>,
    ) -> fmt::Result {
        let metadata = event.metadata();
        let thread = std::thread::current();
        let thread_name = thread
            .name()
            .map_or_else(|| format!("{:?}", thread.id()), str::to_owned);
        let mut message = MessageVisitor::default();
        event.record(&mut message);
        writeln!(
            writer,
            "{} {:<5} --- [{}] [{}] {} : {}{}",
            utc_timestamp(SystemTime::now()),
            metadata.level().as_str(),
            self.name,
            thread_name,
            abbreviate_logger(&metadata.target().replace("::", "."), LOGGER_NAME_WIDTH),
            message.message,
            message.fields,
        )
    }
}

#[derive(Default)]
struct MessageVisitor {
    message: String,
    fields: String,
}

impl Visit for MessageVisitor {
    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.message, "{value:?}");
        } else {
            let _ = write!(self.fields, " {}={value:?}", field.name());
        }
    }
}

/// Formats `time` as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC.
fn utc_timestamp(time: SystemTime) -> String {
    let since_epoch = time.duration_since(UNIX_EPOCH).unwrap_or_default();
    let seconds = since_epoch.as_secs();
    let days = i64::try_from(seconds / 86_400).unwrap_or(0);
    let in_day = seconds % 86_400;
    let shifted = days + 719_468;
    let era = shifted.div_euclid(146_097);
    let day_of_era = shifted.rem_euclid(146_097);
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let shifted_month = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * shifted_month + 2) / 5 + 1;
    let month = if shifted_month < 10 {
        shifted_month + 3
    } else {
        shifted_month - 9
    };
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        in_day / 3_600,
        in_day % 3_600 / 60,
        in_day % 60,
        since_epoch.subsec_millis()
    )
}

/// Shortens leading dot-separated segments of `name` to their first character, left to right,
/// until it fits `width`, as logback's `%logger{width}` does. The last segment is never shortened.
fn abbreviate_logger(name: &str, width: usize) -> String {
    if name.len() <= width {
        return name.to_owned();
    }
    let mut segments: Vec<String> = name.split('.').map(str::to_owned).collect();
    let last = segments.len() - 1;
    for index in 0..last {
        if segments.join(".").len() <= width {
            break;
        }
        let first = segments[index].chars().next().map_or(0, char::len_utf8);
        segments[index].truncate(first);
    }
    segments.join(".")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use tracing_subscriber::layer::SubscriberExt;

    #[derive(Clone, Default)]
    struct Capture(Arc<Mutex<Vec<u8>>>);

    impl std::io::Write for Capture {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl<'a> MakeWriter<'a> for Capture {
        type Writer = Capture;
        fn make_writer(&'a self) -> Capture {
            self.clone()
        }
    }

    fn captured(format: &LogFormat, emit: impl FnOnce()) -> String {
        let capture = Capture::default();
        let subscriber =
            tracing_subscriber::registry().with(format_layer(format, capture.clone(), Some(false)));
        tracing::subscriber::with_default(subscriber, emit);
        let bytes = capture.0.lock().unwrap().clone();
        String::from_utf8(bytes).unwrap()
    }

    struct JavaLine<'a> {
        timestamp: &'a str,
        level: &'a str,
        name: &'a str,
        thread: &'a str,
        logger: &'a str,
        message: &'a str,
    }

    /// Parses a line by the fields of `%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z', UTC} %-5level --- [name]
    /// [%thread] %logger{36} : %msg%n`.
    fn parse_java_line(line: &str) -> Option<JavaLine<'_>> {
        let line = line.strip_suffix('\n')?;
        let (timestamp, rest) = line.split_once(' ')?;
        let level = rest.get(..5)?;
        let rest = rest.get(5..)?.strip_prefix(" --- [")?;
        let (name, rest) = rest.split_once("] [")?;
        let (thread, rest) = rest.split_once("] ")?;
        let (logger, message) = rest.split_once(" : ")?;
        Some(JavaLine {
            timestamp,
            level,
            name,
            thread,
            logger,
            message,
        })
    }

    fn is_java_timestamp(value: &str) -> bool {
        let bytes = value.as_bytes();
        bytes.len() == 24
            && bytes.iter().enumerate().all(|(index, byte)| match index {
                4 | 7 => *byte == b'-',
                10 => *byte == b'T',
                13 | 16 => *byte == b':',
                19 => *byte == b'.',
                23 => *byte == b'Z',
                _ => byte.is_ascii_digit(),
            })
    }

    fn logback(name: &str) -> LogFormat {
        LogFormat::Logback {
            name: name.to_owned(),
        }
    }

    #[test]
    fn a_logback_line_carries_the_fields_of_the_java_pattern() {
        let output = std::thread::Builder::new()
            .name("worker-7".to_owned())
            .spawn(|| {
                captured(&logback("auth-hpds"), || {
                    tracing::info!(target: "hpds_rs::jobs", "store opened");
                })
            })
            .unwrap()
            .join()
            .unwrap();
        let line = parse_java_line(&output).unwrap_or_else(|| panic!("unparsable: {output:?}"));
        assert!(is_java_timestamp(line.timestamp), "{}", line.timestamp);
        assert_eq!(line.level, "INFO ");
        assert_eq!(line.name, "auth-hpds");
        assert_eq!(line.thread, "worker-7");
        assert_eq!(line.logger, "hpds_rs.jobs");
        assert_eq!(line.message, "store opened");
        assert!(output.contains("INFO  --- [auth-hpds] [worker-7]"));
    }

    #[test]
    fn logback_levels_pad_to_five_columns() {
        let output = captured(&logback("hpds"), || {
            tracing::warn!("a");
            tracing::error!("b");
            tracing::debug!("c");
        });
        assert!(output.contains(" WARN  --- "));
        assert!(output.contains(" ERROR --- "));
        assert!(output.contains(" DEBUG --- "));
    }

    #[test]
    fn logback_events_append_their_fields_to_the_message() {
        let output = captured(&logback("hpds"), || {
            tracing::info!(port = 8080, "hpds-rs listening");
        });
        let line = parse_java_line(&output).unwrap();
        assert_eq!(line.message, "hpds-rs listening port=8080");
    }

    #[test]
    fn long_logger_names_are_abbreviated_as_logback_does() {
        assert_eq!(abbreviate_logger("hpds_rs.jobs", 36), "hpds_rs.jobs");
        let name = "edu.harvard.hms.dbmi.avillach.hpds.service.QueryService";
        assert_eq!(
            abbreviate_logger(name, 36),
            "e.h.h.d.a.hpds.service.QueryService"
        );
        assert_eq!(
            abbreviate_logger("a.b.VeryLongClassNameThatCannotFitAnywhereAtAll", 10),
            "a.b.VeryLongClassNameThatCannotFitAnywhereAtAll"
        );
    }

    #[test]
    fn timestamps_are_utc_with_milliseconds() {
        let time = UNIX_EPOCH + std::time::Duration::from_millis(1_782_000_123_456);
        assert_eq!(utc_timestamp(time), "2026-06-21T00:02:03.456Z");
        assert_eq!(utc_timestamp(UNIX_EPOCH), "1970-01-01T00:00:00.000Z");
        let leap = UNIX_EPOCH + std::time::Duration::from_secs(1_709_164_800);
        assert_eq!(utc_timestamp(leap), "2024-02-29T00:00:00.000Z");
    }

    #[test]
    fn the_default_format_is_the_tracing_subscriber_line() {
        let ours = captured(&LogFormat::Default, || {
            tracing::info!(target: "hpds_rs::jobs", port = 8080, "listening");
        });
        let capture = Capture::default();
        let reference = tracing_subscriber::registry().with(
            sub_fmt::layer()
                .with_ansi(false)
                .with_writer(capture.clone()),
        );
        tracing::subscriber::with_default(reference, || {
            tracing::info!(target: "hpds_rs::jobs", port = 8080, "listening");
        });
        let expected = String::from_utf8(capture.0.lock().unwrap().clone()).unwrap();
        let strip = |line: &str| line.split_once(' ').unwrap().1.to_owned();
        assert_eq!(strip(&ours), strip(&expected));
        assert!(
            ours.ends_with("  INFO hpds_rs::jobs: listening port=8080\n"),
            "{ours:?}"
        );
    }

    #[test]
    fn format_values_are_parsed() {
        assert_eq!(
            LogFormat::from_values(None, None).unwrap(),
            LogFormat::Default
        );
        assert_eq!(
            LogFormat::from_values(Some("default"), Some("x")).unwrap(),
            LogFormat::Default
        );
        assert_eq!(
            LogFormat::from_values(Some(" LogBack "), None).unwrap(),
            logback("hpds")
        );
        assert_eq!(
            LogFormat::from_values(Some("logback"), Some("open-hpds")).unwrap(),
            logback("open-hpds")
        );
        assert_eq!(
            LogFormat::from_values(Some("logback"), Some("  ")).unwrap(),
            logback("hpds")
        );
        let error = LogFormat::from_values(Some("json"), None).unwrap_err();
        assert!(
            error.contains("HPDS_LOG_FORMAT")
                && error.contains("logback")
                && error.contains("json"),
            "{error}"
        );
        let error = LogFormat::from_values(Some("logback"), Some("a\nb")).unwrap_err();
        assert!(error.contains("HPDS_LOG_NAME"), "{error}");
    }
}
