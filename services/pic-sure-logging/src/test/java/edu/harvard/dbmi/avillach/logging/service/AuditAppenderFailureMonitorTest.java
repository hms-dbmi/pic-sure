package edu.harvard.dbmi.avillach.logging.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuditAppenderFailureMonitorTest {

    private LoggerContext context;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        registry = new SimpleMeterRegistry();
    }

    @Test
    void auditAppenderFailureIsSuppressedAndIncrementsCounter() {
        try (AuditAppenderFailureMonitor ignored = new AuditAppenderFailureMonitor(context, registry)) {
            AppenderBase<ILoggingEvent> appender = failingAppender("AUDIT_JSON");

            assertThatCode(() -> appender.doAppend(new LoggingEvent())).doesNotThrowAnyException();

            assertThat(registry.get("picsure.audit.appender.failed").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    void nonAuditAppenderFailureIsIgnored() {
        try (AuditAppenderFailureMonitor ignored = new AuditAppenderFailureMonitor(context, registry)) {
            failingAppender("APP_STDERR").doAppend(new LoggingEvent());

            assertThat(registry.get("picsure.audit.appender.failed").counter().count()).isZero();
        }
    }

    @Test
    void closeUnregistersMonitor() {
        AuditAppenderFailureMonitor monitor = new AuditAppenderFailureMonitor(context, registry);
        assertThat(context.getStatusManager().getCopyOfStatusListenerList()).contains(monitor);

        monitor.close();

        assertThat(context.getStatusManager().getCopyOfStatusListenerList()).doesNotContain(monitor);
        failingAppender("AUDIT_FILE").doAppend(new LoggingEvent());
        assertThat(registry.get("picsure.audit.appender.failed").counter().count()).isZero();
    }

    private AppenderBase<ILoggingEvent> failingAppender(String name) {
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                throw new IllegalStateException("synthetic appender failure");
            }
        };
        appender.setContext(context);
        appender.setName(name);
        appender.start();
        return appender;
    }
}
