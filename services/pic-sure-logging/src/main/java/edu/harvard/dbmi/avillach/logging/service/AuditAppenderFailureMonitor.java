package edu.harvard.dbmi.avillach.logging.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Component
public final class AuditAppenderFailureMonitor implements StatusListener, AutoCloseable {

    private static final Set<String> AUDIT_APPENDER_NAMES = Set.of("AUDIT_JSON", "AUDIT_FILE", "ASYNC_AUDIT_FILE");

    private final StatusManager statusManager;
    private final Counter appenderFailedCounter;

    @Autowired
    public AuditAppenderFailureMonitor(MeterRegistry meterRegistry) {
        this(currentLoggerContext(), meterRegistry);
    }

    AuditAppenderFailureMonitor(LoggerContext context, MeterRegistry meterRegistry) {
        this.statusManager = context.getStatusManager();
        this.appenderFailedCounter = meterRegistry.counter("picsure.audit.appender.failed");
        statusManager.add(this);
    }

    @Override
    public void addStatusEvent(Status status) {
        if (
            status.getLevel() == Status.ERROR && status.getOrigin() instanceof Appender<?> appender
                && AUDIT_APPENDER_NAMES.contains(appender.getName())
        ) {
            appenderFailedCounter.increment();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        statusManager.remove(this);
    }

    private static LoggerContext currentLoggerContext() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            return loggerContext;
        }
        throw new IllegalStateException("Logback LoggerContext is required to monitor audit appender failures");
    }
}
