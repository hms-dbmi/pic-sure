package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers the display-string parser: exact, obfuscated with variance, suppressed, and malformed strings, and the JSON it emits. */
class CountResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesACountWithVariance() throws Exception {
        CountResult result = CountResult.parse("1234 ±3");

        assertThat(result).isEqualTo(new CountResult("1234 ±3", 1234, 3, null, false));
        assertThat(JSON.writeValueAsString(result))
            .isEqualTo("{\"display\":\"1234 ±3\",\"count\":1234,\"variance\":3,\"suppressed\":false}");
    }

    @Test
    void parsesAnExactCount() throws Exception {
        assertThat(CountResult.parse("57")).isEqualTo(new CountResult("57", 57, null, null, false));
        assertThat(CountResult.parse("57").isExact()).isTrue();
        assertThat(JSON.writeValueAsString(CountResult.parse("57"))).doesNotContain("exact");
    }

    @Test
    void parsesAnExactCountUpToTheIntRange() {
        assertThat(CountResult.parse("1234567890").count()).isEqualTo(1234567890);
        assertThat(CountResult.parse(String.valueOf(Integer.MAX_VALUE)).isExact()).isTrue();
    }

    @Test
    void anObfuscatedOrSuppressedCountIsNotExact() {
        assertThat(CountResult.parse("1234 ±3").isExact()).isFalse();
        assertThat(CountResult.parse("< 10").isExact()).isFalse();
        assertThat(CountResult.parse("-1").isExact()).isFalse();
    }

    @Test
    void parsesASuppressedCount() throws Exception {
        CountResult result = CountResult.parse("< 10");

        assertThat(result).isEqualTo(new CountResult("< 10", null, null, 10, true));
        assertThat(JSON.writeValueAsString(result)).isEqualTo("{\"display\":\"< 10\",\"threshold\":10,\"suppressed\":true}");
    }

    @Test
    void acceptsAJsonQuotedDisplayAndSurroundingWhitespace() {
        assertThat(CountResult.parse(" \"1234 ±3\"\n")).isEqualTo(new CountResult("1234 ±3", 1234, 3, null, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "about 40", "12 ±", "-5", "99999999999", "2147483648", "1234 ±3 ±4", "<", "{\"a\":1}"})
    void returnsAMalformedDisplayAloneWithoutFailing(String display) throws Exception {
        CountResult result = CountResult.parse(display);

        assertThat(result.display()).isEqualTo(display);
        assertThat(result.count()).isNull();
        assertThat(result.variance()).isNull();
        assertThat(result.threshold()).isNull();
        assertThat(result.suppressed()).isNull();
        assertThat(JSON.readTree(JSON.writeValueAsString(result)).fieldNames()).toIterable().containsExactly("display");
    }

    @Test
    void nullBecomesAnEmptyDisplayAndLongDisplaysAreCapped() {
        assertThat(CountResult.parse(null)).isEqualTo(new CountResult("", null, null, null, null));
        assertThat(CountResult.parse("x".repeat(500)).display()).hasSize(CountResult.MAX_DISPLAY_LENGTH);
    }
}
