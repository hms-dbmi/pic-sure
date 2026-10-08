package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyBearerTest {

    @ParameterizedTest
    @ValueSource(
        strings = {"Bearer picsure_u_key", "bearer picsure_u_key", "BEARER picsure_u_key", "Bearer  picsure_u_key ",
            "Bearer picsure_u_key\t"}
    )
    void extractsAKeyWhateverTheSchemeCaseOrSurroundingBlanks(String authorization) {
        assertThat(ApiKeyBearer.extract(authorization)).isEqualTo("picsure_u_key");
    }

    @Test
    void extractsEveryKeyType() {
        assertThat(ApiKeyBearer.extract("Bearer picsure_p_key")).isEqualTo("picsure_p_key");
        assertThat(ApiKeyBearer.extract("Bearer picsure_s_eyJ.session.token")).isEqualTo("picsure_s_eyJ.session.token");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {"", "Bearer", "Bearer ", "Bearer eyJhbGciOiJIUzI1NiJ9.login.token", "Bearerpicsure_u_key", "Bearer\tpicsure_u_key",
            " Bearer picsure_u_key", "Basic picsure_u_key", "picsure_u_key", "Bearer PICSURE_u_key"}
    )
    void everythingElseIsNotAKey(String authorization) {
        assertThat(ApiKeyBearer.extract(authorization)).isNull();
    }
}
