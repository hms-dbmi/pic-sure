package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Test-only Logback appender that captures every line emitted to a given logger in-memory, so gateway-filter observe-mode tests can assert
 * on {@code picsure.shadow} log output without parsing real log files. Mirrors {@code ShadowSupportTest}'s inline {@code ListAppender}
 * usage but packaged for reuse across {@code PsamaIntrospectionFilterObserveTest} and {@code OpenAccessFilterObserveTest} (Tasks 4-5).
 */
public class ShadowTestAppender extends AppenderBase<ILoggingEvent> {

    private final List<String> lines = new CopyOnWriteArrayList<>();

    /** Attaches a fresh, started appender to {@code loggerName} and returns it so callers can inspect {@link #lines()}. */
    public static ShadowTestAppender attach(String loggerName) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(loggerName);
        ShadowTestAppender appender = new ShadowTestAppender();
        appender.setContext((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent event) {
        lines.add(event.getFormattedMessage());
    }

    public List<String> lines() {
        return lines;
    }
}
