package edu.harvard.hms.dbmi.avillach.query.hpds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

class HpdsBackendSelectorTest {

    private HpdsBackendSelector selector() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds-auth:8080/PIC-SURE");
        p.setAuthToken("auth-secret");
        p.setOpenUrl("http://hpds-open:8080/PIC-SURE");
        p.setOpenToken("open-secret");
        return new HpdsBackendSelector(p);
    }

    @Test
    void authAppendsV3KeepsToken() {
        var t = selector().select("auth");
        assertThat(t.baseUrl()).isEqualTo("http://hpds-auth:8080/PIC-SURE/v3");
        assertThat(t.token()).isEqualTo("auth-secret");
    }

    @Test
    void openAppendsV3KeepsToken() {
        var t = selector().select("open");
        assertThat(t.baseUrl()).isEqualTo("http://hpds-open:8080/PIC-SURE/v3");
        assertThat(t.token()).isEqualTo("open-secret");
    }

    @Test
    void unknownBackendThrows() {
        assertThatThrownBy(() -> selector().select("bogus")).isInstanceOf(PicsureException.class);
    }

    @Test
    void nullBackendThrows() {
        assertThatThrownBy(() -> selector().select(null)).isInstanceOf(PicsureException.class);
    }

    /**
     * A deployment without an open HPDS instance leaves {@code HPDS_OPEN_URL} unset, which binds to the empty string. Selecting that
     * backend must fail fast with a clear 503 rather than composing a bogus base ("" or "/v3") that surfaces later as an opaque 500.
     */
    @Test
    void unconfiguredBackendThrowsServiceUnavailable() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds-auth:8080/PIC-SURE");
        p.setOpenUrl("");

        HpdsBackendSelector selector = new HpdsBackendSelector(p);

        PicsureException thrown = assertThrows(PicsureException.class, () -> selector.select("open"));

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void configuredApiPathReplacesTheV3Default() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds-auth:8080/PIC-SURE");
        p.setApiPath("/v4");

        assertThat(new HpdsBackendSelector(p).select("auth").baseUrl()).isEqualTo("http://hpds-auth:8080/PIC-SURE/v4");
    }

    @Test
    void emptyApiPathTargetsTheBaseUrlItself() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds-auth:8080/PIC-SURE");
        p.setApiPath("");

        assertThat(new HpdsBackendSelector(p).select("auth").baseUrl()).isEqualTo("http://hpds-auth:8080/PIC-SURE");
    }
}
