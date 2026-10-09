use std::time::Duration;

use crate::{BenchError, Result};

/// Parses `500ms`, `10s`, `2m` or `1h`. A bare number means seconds.
pub fn parse_duration(text: &str) -> Result<Duration> {
    let trimmed = text.trim();
    let split = trimmed
        .find(|c: char| !(c.is_ascii_digit() || c == '.'))
        .unwrap_or(trimmed.len());
    let (number, unit) = trimmed.split_at(split);
    let value: f64 = number
        .parse()
        .map_err(|_| BenchError::Invalid(format!("invalid duration '{text}'")))?;
    let seconds = match unit {
        "" | "s" => value,
        "ms" => value / 1000.0,
        "m" => value * 60.0,
        "h" => value * 3600.0,
        _ => {
            return Err(BenchError::Invalid(format!(
                "invalid duration unit in '{text}'"
            )));
        }
    };
    Ok(Duration::from_secs_f64(seconds))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_units() {
        assert_eq!(parse_duration("60s").unwrap(), Duration::from_secs(60));
        assert_eq!(parse_duration("500ms").unwrap(), Duration::from_millis(500));
        assert_eq!(parse_duration("2m").unwrap(), Duration::from_secs(120));
        assert_eq!(parse_duration("5").unwrap(), Duration::from_secs(5));
    }

    #[test]
    fn rejects_garbage() {
        assert!(parse_duration("abc").is_err());
        assert!(parse_duration("5x").is_err());
    }
}
