package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** How the gateway reads {@code DEST_PORT}: a value it cannot use counts as unset and never fails startup. */
@ExtendWith(OutputCaptureExtension.class)
class AuditFilterConfigDestPortTest {

    @Test
    void aNumberIsThePort() {
        assertThat(AuditFilterConfig.destPortFrom("443")).isEqualTo(443);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void anAbsentOrBlankValueIsUnsetAndLogsNothing(String configured, CapturedOutput output) {
        assertThat(AuditFilterConfig.destPortFrom(configured)).isNull();
        assertThat(output.getAll()).doesNotContain("DEST_PORT");
    }

    @Test
    void aValueThatIsNotANumberIsUnsetAndLogsOneWarningNamingTheVariable(CapturedOutput output) {
        assertThat(AuditFilterConfig.destPortFrom("abc")).isNull();

        assertThat(output.getAll()).containsOnlyOnce("DEST_PORT").contains("WARN").doesNotContain("abc");
    }
}
