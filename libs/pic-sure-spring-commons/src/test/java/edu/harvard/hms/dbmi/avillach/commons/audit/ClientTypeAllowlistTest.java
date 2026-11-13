package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ClientTypeAllowlistTest {

    @Test
    void listedValueResolvesToItsListedSpellingIgnoringCase() {
        assertThat(ClientTypeAllowlist.DEFAULT.resolve(" python_adapter ")).contains("PYTHON_ADAPTER");
    }

    @Test
    void unlistedNullAndBlankValuesDoNotResolve() {
        assertThat(ClientTypeAllowlist.DEFAULT.resolve("curl-script")).isEmpty();
        assertThat(ClientTypeAllowlist.DEFAULT.resolve(null)).isEmpty();
        assertThat(ClientTypeAllowlist.DEFAULT.resolve("  ")).isEmpty();
    }

    @Test
    void reservedValuesCannotBeListed() {
        assertThatThrownBy(() -> new ClientTypeAllowlist(List.of("FRONTEND", "MCP"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientTypeAllowlist(List.of("service"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankEntriesAreIgnored() {
        assertThat(new ClientTypeAllowlist(List.of("", " ", "FRONTEND")).resolve("frontend")).contains("FRONTEND");
    }
}
