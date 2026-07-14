package edu.harvard.dbmi.avillach.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogbackConfigTest {

    @TempDir
    Path tempDir;

    private LoggerContext context;

    @BeforeEach
    void setUp() throws Exception {
        context = new LoggerContext();
        context.putProperty("LOG_DIR", tempDir.toString());

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        try (InputStream is = getClass().getResourceAsStream("/logback.xml")) {
            assertNotNull(is, "logback.xml must be on the classpath");
            configurator.doConfigure(is);
        }
        context.start();
    }

    @Test
    void configurationLoadsWithoutErrors() {
        List<Status> statusList = context.getStatusManager().getCopyOfStatusList();
        List<Status> errors = statusList.stream()
            .filter(s -> s.getLevel() == Status.ERROR)
            .toList();
        assertTrue(errors.isEmpty(),
            "Logback config should load without errors, but got: " + errors);
    }

    @Test
    void configurationLoadsWithoutWarnings() {
        List<Status> statusList = context.getStatusManager().getCopyOfStatusList();
        List<Status> warnings = statusList.stream()
            .filter(s -> s.getLevel() == Status.WARN)
            .toList();
        assertTrue(warnings.isEmpty(),
            "Logback config should load without warnings, but got: " + warnings);
    }

    // --- AUDIT logger appender tests ---

    @Test
    void auditLoggerHasAsyncFileAppender() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender appender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        assertNotNull(appender, "AUDIT logger should have ASYNC_AUDIT_FILE appender");
    }

    @Test
    void auditAsyncAppenderWrapsFileAppender() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        // The async appender should delegate to the underlying AUDIT_FILE
        Appender<ILoggingEvent> delegate = asyncAppender.getAppender("AUDIT_FILE");
        assertNotNull(delegate, "ASYNC_AUDIT_FILE should wrap AUDIT_FILE");
        assertInstanceOf(RollingFileAppender.class, delegate);
    }

    @Test
    void auditFileAppenderTargetsCorrectFile() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("AUDIT_FILE");
        assertEquals(tempDir.resolve("audit.log").toString(), fileAppender.getFile());
    }

    @Test
    void auditFileAppenderUsesSizeAndTimeRollingPolicy() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("AUDIT_FILE");
        assertInstanceOf(SizeAndTimeBasedRollingPolicy.class, fileAppender.getRollingPolicy());
    }

    @Test
    void auditFileAppenderHasCorrectMaxHistory() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("AUDIT_FILE");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) fileAppender.getRollingPolicy();
        assertEquals(30, policy.getMaxHistory());
    }

    @Test
    void auditFileAppenderHasDateAndIndexInPattern() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("AUDIT_FILE");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) fileAppender.getRollingPolicy();
        String pattern = policy.getFileNamePattern();
        assertTrue(pattern.contains("audit.%d{yyyy-MM-dd}.%i.log"),
            "Rolling pattern should include date and index for size+time rotation");
    }

    // --- APP (root) logger appender tests ---

    @Test
    void rootLoggerHasAsyncFileAppender() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender appender = findAppender(rootLogger, "ASYNC_APP_FILE");
        assertNotNull(appender, "Root logger should have ASYNC_APP_FILE appender");
    }

    @Test
    void appAsyncAppenderWrapsFileAppender() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        Appender<ILoggingEvent> delegate = asyncAppender.getAppender("APP_FILE");
        assertNotNull(delegate, "ASYNC_APP_FILE should wrap APP_FILE");
        assertInstanceOf(RollingFileAppender.class, delegate);
    }

    @Test
    void appFileAppenderTargetsCorrectFile() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("APP_FILE");
        assertEquals(tempDir.resolve("app.log").toString(), fileAppender.getFile());
    }

    @Test
    void appFileAppenderUsesSizeAndTimeRollingPolicy() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("APP_FILE");
        assertInstanceOf(SizeAndTimeBasedRollingPolicy.class, fileAppender.getRollingPolicy());
    }

    @Test
    void appFileAppenderHasCorrectMaxHistory() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("APP_FILE");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) fileAppender.getRollingPolicy();
        assertEquals(30, policy.getMaxHistory());
    }

    @Test
    void appFileAppenderHasDateAndIndexInPattern() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        RollingFileAppender<ILoggingEvent> fileAppender =
            (RollingFileAppender<ILoggingEvent>) asyncAppender.getAppender("APP_FILE");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) fileAppender.getRollingPolicy();
        String pattern = policy.getFileNamePattern();
        assertTrue(pattern.contains("app.%d{yyyy-MM-dd}.%i.log"),
            "Rolling pattern should include date and index for size+time rotation");
    }

    // --- Functional tests: logs actually get written to files ---

    @Test
    void rollingFileAppenderWritesToDisk() throws Exception {
        LoggerContext defaultContext =
            (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(defaultContext);
        appender.setFile(tempDir.resolve("rolling-test.log").toString());

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(defaultContext);
        policy.setParent(appender);
        policy.setFileNamePattern(tempDir.resolve("rolling-test.%d{yyyy-MM-dd}.%i.log").toString());
        policy.setMaxFileSize(new ch.qos.logback.core.util.FileSize(50 * ch.qos.logback.core.util.FileSize.MB_COEFFICIENT));
        policy.setMaxHistory(30);
        policy.setTotalSizeCap(new ch.qos.logback.core.util.FileSize(500 * ch.qos.logback.core.util.FileSize.MB_COEFFICIENT));
        policy.start();
        appender.setRollingPolicy(policy);

        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(defaultContext);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();
        appender.setEncoder(encoder);
        appender.start();

        Logger testLogger = defaultContext.getLogger("ROLLING_FILE_TEST");
        testLogger.setAdditive(false);
        testLogger.addAppender(appender);

        testLogger.info("rolling file test event");
        appender.stop();
        testLogger.detachAppender(appender);

        Path testLog = tempDir.resolve("rolling-test.log");
        assertTrue(Files.exists(testLog), "rolling-test.log should be created");
        String content = Files.readString(testLog);
        assertTrue(content.contains("rolling file test event"),
            "RollingFileAppender with SizeAndTimeBasedRollingPolicy should write events to disk");
    }

    @Test
    void auditLoggerDoesNotPropagateToRoot() {
        Logger auditLogger = context.getLogger("AUDIT");
        assertFalse(auditLogger.isAdditive(), "AUDIT logger should have additivity=false");
    }

    @Test
    void auditEventsDoNotAppearInAppLog() throws Exception {
        Logger auditLogger = context.getLogger("AUDIT");
        auditLogger.info("secret audit data");

        Path appLog = tempDir.resolve("app.log");
        if (Files.exists(appLog)) {
            String content = Files.readString(appLog);
            assertFalse(content.contains("secret audit data"),
                "Audit events should not leak into app.log");
        }
    }

    // --- Async appender configuration tests ---

    @Test
    void auditAsyncAppenderHasCorrectQueueSize() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        assertEquals(1024, asyncAppender.getQueueSize());
    }

    @Test
    void appAsyncAppenderHasCorrectQueueSize() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        assertEquals(512, asyncAppender.getQueueSize());
    }

    @Test
    void auditAsyncAppenderNeverDiscards() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        assertEquals(0, asyncAppender.getDiscardingThreshold());
    }

    @Test
    void appAsyncAppenderNeverDiscards() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        assertEquals(0, asyncAppender.getDiscardingThreshold());
    }

    @Test
    void auditAsyncAppenderNeverBlocks() {
        Logger auditLogger = context.getLogger("AUDIT");
        AsyncAppender asyncAppender = findAppender(auditLogger, "ASYNC_AUDIT_FILE");
        assertTrue(asyncAppender.isNeverBlock());
    }

    @Test
    void appAsyncAppenderNeverBlocks() {
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        AsyncAppender asyncAppender = findAppender(rootLogger, "ASYNC_APP_FILE");
        assertTrue(asyncAppender.isNeverBlock());
    }

    // --- Helper ---

    @SuppressWarnings("unchecked")
    private <T extends Appender<ILoggingEvent>> T findAppender(Logger logger, String name) {
        Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ILoggingEvent> appender = it.next();
            if (name.equals(appender.getName())) {
                return (T) appender;
            }
        }
        return null;
    }
}
