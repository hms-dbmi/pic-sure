package edu.harvard.hms.dbmi.avillach.query.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures every event logged through the root logger while open, and renders each as the text a log sink could hold: the formatted
 * message, the logger and thread names, the MDC values, and the class names and messages of any attached throwable chain. While open, the
 * root logger and {@link HpdsRouter}'s logger log at DEBUG, so a body written at a level the default configuration hides is caught too;
 * closing restores both levels.
 */
final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger router = (Logger) LoggerFactory.getLogger(HpdsRouter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level rootLevel = root.getLevel();
    private final Level routerLevel = router.getLevel();

    LogCapture() {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        router.setLevel(Level.DEBUG);
    }

    /** @return the text of every captured event, in order */
    List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            StringBuilder text = new StringBuilder().append(event.getLoggerName()).append(' ').append(event.getThreadName()).append(' ')
                .append(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(' ').append(proxy.getClassName()).append(' ').append(proxy.getMessage());
            }
            lines.add(text.toString());
        }
        return lines;
    }

    /** @return the captured lines that came from {@link HpdsRouter} */
    List<String> routerLines() {
        return lines().stream().filter(line -> line.startsWith(HpdsRouter.class.getName())).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        root.setLevel(rootLevel);
        router.setLevel(routerLevel);
    }

    /**
     * The forms a body could take in a log line: the text itself, and its MD5, SHA-1 and SHA-256 digests in lower and upper hex and in
     * base64, and its Java hash codes.
     *
     * @param body the body text
     * @return every form, the text first
     */
    static List<String> forbiddenForms(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<String> forms = new ArrayList<>();
        forms.add(body);
        for (String algorithm : List.of("MD5", "SHA-1", "SHA-256")) {
            byte[] digest = digest(algorithm, bytes);
            String hex = HexFormat.of().formatHex(digest);
            forms.add(hex);
            forms.add(hex.toUpperCase(Locale.ENGLISH));
            forms.add(Base64.getEncoder().encodeToString(digest));
            forms.add(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        }
        forms.add(Integer.toString(body.hashCode()));
        forms.add(Integer.toString(Arrays.hashCode(bytes)));
        return forms;
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
