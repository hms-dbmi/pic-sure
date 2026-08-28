package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies the gateway routes that are intentionally reachable without authentication. */
public final class PublicEndpointPolicy {

    public static final String SYSTEM_MONITOR = "SYSTEM_MONITOR";

    private static final Pattern CONFIGURATION_ID_READ = Pattern.compile("^/operations/configuration/([^/]+)/?$");
    private static final Decision PROTECTED = new Decision(false, Optional.empty());
    private static final Decision PUBLIC = new Decision(true, Optional.empty());
    private static final Decision SYSTEM_STATUS = new Decision(true, Optional.of(SYSTEM_MONITOR));

    private final List<String> allowListPrefixes;

    public PublicEndpointPolicy(List<String> allowListPrefixes) {
        this.allowListPrefixes = allowListPrefixes == null ? List.of() : List.copyOf(allowListPrefixes);
    }

    public Decision evaluate(String method, String path) {
        if (method == null || path == null) {
            return PROTECTED;
        }
        if ("GET".equals(method) && path.equals("/system/status")) {
            return SYSTEM_STATUS;
        }
        if (path.endsWith("/openapi.json")) {
            return PUBLIC;
        }
        for (String prefix : allowListPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return PUBLIC;
            }
        }
        if (!"GET".equals(method)) {
            return PROTECTED;
        }
        if (path.equals("/operations/banners/active") || path.equals("/operations/banners/active/v2")) {
            return PUBLIC;
        }
        if (path.equals("/operations/configuration") || path.equals("/operations/configuration/")) {
            return PUBLIC;
        }
        Matcher configurationRead = CONFIGURATION_ID_READ.matcher(path);
        return configurationRead.matches() && !"admin".equals(configurationRead.group(1)) ? PUBLIC : PROTECTED;
    }

    public record Decision(boolean publicEndpoint, Optional<String> auditUsername) {
    }
}
