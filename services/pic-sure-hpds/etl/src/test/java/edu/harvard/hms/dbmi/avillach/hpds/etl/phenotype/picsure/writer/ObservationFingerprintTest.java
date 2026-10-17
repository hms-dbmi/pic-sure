package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ObservationFingerprintTest {

    private static final String A = "\\phs000000\\Study\\A\\";

    @Test
    void numericValuesAreNormalisedSoEquivalentSpellingsMatch() {
        Instant t = Instant.parse("2021-01-15T10:30:00Z");
        ObservationFingerprint first = ObservationFingerprint.from(new ObservationRow(1, A, 12.3, null, t));
        ObservationFingerprint second = ObservationFingerprint.from(new ObservationRow(1, A, Double.parseDouble("12.30"), null, t));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differencesInAnyFieldMakeObservationsDistinct() {
        Instant t = Instant.parse("2021-01-15T10:30:00Z");
        ObservationFingerprint base = ObservationFingerprint.from(new ObservationRow(1, A, null, "x", t));

        assertNotEquals(base, ObservationFingerprint.from(new ObservationRow(2, A, null, "x", t)));
        assertNotEquals(base, ObservationFingerprint.from(new ObservationRow(1, A + "b\\", null, "x", t)));
        assertNotEquals(base, ObservationFingerprint.from(new ObservationRow(1, A, null, "y", t)));
        assertNotEquals(base, ObservationFingerprint.from(new ObservationRow(1, A, null, "x", t.plusMillis(1))));
    }

    @Test
    void missingTimestampFingerprintsAsZero() {
        assertEquals(0L, ObservationFingerprint.from(new ObservationRow(1, A, 1.0, null, null)).timestampEpochMillis());
    }
}
