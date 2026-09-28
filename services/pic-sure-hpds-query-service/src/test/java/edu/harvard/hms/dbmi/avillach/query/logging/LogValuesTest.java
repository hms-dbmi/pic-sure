package edu.harvard.hms.dbmi.avillach.query.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogValuesTest {

    @Test
    void replacesLineBreaksAndOtherControlCharacters() {
        assertThat(LogValues.of("COUNT\r\n2026-09-28 INFO forged\tentry\u0000")).isEqualTo("COUNT__2026-09-28 INFO forged_entry_");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(LogValues.of("DATAFRAME")).isEqualTo("DATAFRAME");
    }

    @Test
    void nullBecomesTheStringNull() {
        assertThat(LogValues.of((Object) null)).isEqualTo("null");
    }

    @Test
    void cutsLongValues() {
        String safe = LogValues.of("x".repeat(LogValues.MAX_LENGTH + 50));

        assertThat(safe).hasSize(LogValues.MAX_LENGTH + LogValues.TRUNCATION_MARKER.length()).endsWith(LogValues.TRUNCATION_MARKER);
    }

    @Test
    void describesAnExceptionByClassAndSafeMessage() {
        assertThat(LogValues.of(new IllegalArgumentException("bad\ninput"))).isEqualTo("IllegalArgumentException: bad_input");
    }
}
