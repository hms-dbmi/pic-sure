package edu.harvard.hms.dbmi.avillach.query.hpds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
    void authV1() {
        var t = selector().select("auth", false);
        assertThat(t.baseUrl()).isEqualTo("http://hpds-auth:8080/PIC-SURE");
        assertThat(t.token()).isEqualTo("auth-secret");
    }

    @Test
    void openV1() {
        var t = selector().select("open", false);
        assertThat(t.baseUrl()).isEqualTo("http://hpds-open:8080/PIC-SURE");
        assertThat(t.token()).isEqualTo("open-secret");
    }

    @Test
    void authV3AppendsV3KeepsToken() {
        var t = selector().select("auth", true);
        assertThat(t.baseUrl()).isEqualTo("http://hpds-auth:8080/PIC-SURE/v3");
        assertThat(t.token()).isEqualTo("auth-secret");
    }

    @Test
    void openV3AppendsV3KeepsToken() {
        var t = selector().select("open", true);
        assertThat(t.baseUrl()).isEqualTo("http://hpds-open:8080/PIC-SURE/v3");
        assertThat(t.token()).isEqualTo("open-secret");
    }

    @Test
    void unknownBackendThrows() {
        assertThatThrownBy(() -> selector().select("bogus", false)).isInstanceOf(PicsureException.class);
    }

    @Test
    void nullBackendThrows() {
        assertThatThrownBy(() -> selector().select(null, false)).isInstanceOf(PicsureException.class);
    }
}
