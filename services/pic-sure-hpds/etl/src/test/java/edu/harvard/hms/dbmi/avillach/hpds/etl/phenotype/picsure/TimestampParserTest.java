package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TimestampParser - validates the parsing chain for the allConcepts timestamp column.
 */
class TimestampParserTest {

    @Test
    void parsesIso8601WithZone() {
        assertEquals(Instant.parse("2021-01-15T10:30:00Z"), TimestampParser.parse("2021-01-15T10:30:00Z"));
        assertEquals(Instant.parse("2021-01-15T10:30:00Z"), TimestampParser.parse("2021-01-15T10:30:00+00:00"));
    }

    @Test
    void parsesSpaceSeparatedAsUtc() {
        // EHR export format: space-separated, zoneless, treated as UTC
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), TimestampParser.parse("2023-11-25 14:00:00"));
        assertEquals(Instant.parse("2024-06-27T00:00:00Z"), TimestampParser.parse("2024-06-27 00:00:00"));
    }

    @Test
    void parsesSpaceSeparatedWithFractionalSecondsAsUtc() {
        // EHR exports mix fractional-second precision on the same column
        assertEquals(Instant.parse("2020-01-02T03:04:05.678Z"), TimestampParser.parse("2020-01-02 03:04:05.678"));
        assertEquals(Instant.parse("2021-06-07T08:09:10.900Z"), TimestampParser.parse("2021-06-07 08:09:10.9"));
        assertEquals(Instant.parse("2022-03-04T05:06:07.030Z"), TimestampParser.parse("2022-03-04 05:06:07.03"));
    }

    @Test
    void parsesIsoLocalDateTimeAsUtc() {
        assertEquals(Instant.parse("2025-02-28T12:56:40.500Z"), TimestampParser.parse("2025-02-28T12:56:40.500"));
    }

    @Test
    void parsesIsoLocalDateAsMidnightUtc() {
        assertEquals(Instant.parse("2025-02-28T00:00:00Z"), TimestampParser.parse("2025-02-28"));
    }

    @Test
    void throwsOnGarbage() {
        assertThrows(DateTimeParseException.class, () -> TimestampParser.parse("invalid-timestamp"));
        assertThrows(DateTimeParseException.class, () -> TimestampParser.parse("25/11/2023"));
        assertThrows(DateTimeParseException.class, () -> TimestampParser.parse("1700000000000.5"));
    }

    @Test
    void throwsOnOutOfRangeFields() {
        assertThrows(DateTimeParseException.class, () -> TimestampParser.parse("2023-13-45 99:00:00"));
    }

    @Test
    void parsesEpochMillisecondsAsIs() {
        assertEquals(Instant.ofEpochMilli(1700000000000L), TimestampParser.parse("1700000000000"));
        assertEquals(Instant.parse("2021-01-15T10:30:00Z"), TimestampParser.parse("1610706600000"));
        assertEquals(Instant.ofEpochMilli(1), TimestampParser.parse("1"));
        assertEquals(Instant.ofEpochMilli(-86400000L), TimestampParser.parse("-86400000"));
    }

    @Test
    void epochIsNotUnitDetected() {
        // a ten digit epoch-seconds value is read as milliseconds, exactly like the legacy CSV loaders
        assertEquals(Instant.ofEpochMilli(1700000000L), TimestampParser.parse("1700000000"));
    }

    @Test
    void epochToleratesSurroundingWhitespace() {
        assertEquals(Instant.ofEpochMilli(1700000000000L), TimestampParser.parse(" 1700000000000 "));
    }

    @Test
    void zeroBlankAndNullMeanNoTimestamp() {
        assertNull(TimestampParser.parse("0"));
        assertNull(TimestampParser.parse(" 0 "));
        assertNull(TimestampParser.parse(""));
        assertNull(TimestampParser.parse("   "));
        assertNull(TimestampParser.parse(null));
    }

    @Test
    void parsesIsoWithNonUtcOffsetToTheSameInstant() {
        assertEquals(Instant.parse("2021-01-15T08:30:00Z"), TimestampParser.parse("2021-01-15T10:30:00+02:00"));
        assertEquals(Instant.parse("2021-01-15T15:30:00Z"), TimestampParser.parse("2021-01-15T10:30:00-05:00"));
    }

    @Test
    void zonelessAndDateOnlyAreUtcRegardlessOfTheJvmZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            assertEquals(Instant.parse("2023-11-25T14:00:00Z"), TimestampParser.parse("2023-11-25 14:00:00"));
            assertEquals(Instant.parse("2025-02-28T12:56:40Z"), TimestampParser.parse("2025-02-28T12:56:40"));
            assertEquals(Instant.parse("2025-02-28T00:00:00Z"), TimestampParser.parse("2025-02-28"));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }
}
