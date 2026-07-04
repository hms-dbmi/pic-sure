package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Task 6: {@link ShadowTestAppender} captures exactly the lines {@link ShadowSupport#emit} writes to the named logger, so filter tests
 * (Tasks 4-5) can assert on shadow output without touching real log files.
 */
class ShadowTestAppenderTest {

    private ShadowTestAppender appender;

    @AfterEach
    void detach() {
        if (appender != null) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("picsure.shadow")).detachAppender(appender);
        }
    }

    @Test
    void capturesEmittedLines() {
        appender = ShadowTestAppender.attach("picsure.shadow");

        ShadowSupport.emit(ShadowRecord.gwIntrospection("c", "h", "/p", Map.of()));

        assertThat(appender.lines()).hasSize(1);
        assertThat(appender.lines().get(0)).contains("\"correlationId\":\"c\"");
    }
}
