package edu.harvard.hms.dbmi.avillach.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JWTUtilIsApplicationTokenTest {

    @Test
    void applicationSubjectIsAnApplicationToken() {
        assertThat(JWTUtil.isApplicationToken(AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|app-uuid")).isTrue();
    }

    @Test
    void userAndLongTermSubjectsAreNot() {
        assertThat(JWTUtil.isApplicationToken("researcher-subject")).isFalse();
        assertThat(JWTUtil.isApplicationToken(AuthNaming.LONG_TERM_TOKEN_PREFIX + "|researcher-subject")).isFalse();
    }

    @Test
    void missingSubjectIsNot() {
        assertThat(JWTUtil.isApplicationToken(null)).isFalse();
    }
}
