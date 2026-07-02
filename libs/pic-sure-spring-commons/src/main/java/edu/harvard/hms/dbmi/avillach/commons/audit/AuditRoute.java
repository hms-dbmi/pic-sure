package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.regex.Pattern;

/**
 * A single audit route rule, re-expressing the shape of the legacy AuditLoggingFilter's route table (a {@code Pattern} plus an optional
 * HTTP method, mapped to an {@code eventType}/{@code action} pair). {@code method == null} means "any method". {@code useFind} selects
 * {@code matcher.find()} (pattern may match anywhere in the path) vs {@code matcher.matches()} (pattern must match the whole path).
 */
public final class AuditRoute {

    private final Pattern pattern;
    private final String method;
    private final String eventType;
    private final String action;
    private final boolean useFind;

    public AuditRoute(Pattern pattern, String method, String eventType, String action) {
        this(pattern, method, eventType, action, false);
    }

    public AuditRoute(Pattern pattern, String method, String eventType, String action, boolean useFind) {
        this.pattern = pattern;
        this.method = method;
        this.eventType = eventType;
        this.action = action;
        this.useFind = useFind;
    }

    public boolean matches(String path, String httpMethod) {
        boolean patternMatch = useFind ? pattern.matcher(path).find() : pattern.matcher(path).matches();
        return patternMatch && (method == null || method.equals(httpMethod));
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getMethod() {
        return method;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAction() {
        return action;
    }

    public boolean isUseFind() {
        return useFind;
    }
}
