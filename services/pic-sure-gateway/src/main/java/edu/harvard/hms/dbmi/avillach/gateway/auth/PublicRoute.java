package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One route the gateway serves without a token, bound from an entry of {@code picsure.gateway.security.public-routes}.
 *
 * @param path the configured path; must start with {@code /}
 * @param match how {@code path} is compared against the request path
 * @param methods HTTP methods the entry applies to; empty means any method
 * @param deny child segments that stay protected; only meaningful for {@link MatchKind#SINGLE_SEGMENT_CHILD}
 * @param auditUsername identity stamped on the audit record for a matched request; blank means none
 * @throws IllegalArgumentException on a blank path, a path without a leading slash, a missing match kind, a blank method, or a deny list on
 *         a kind that cannot apply it
 */
public record PublicRoute(String path, MatchKind match, Set<String> methods, Set<String> deny, String auditUsername) {

    public PublicRoute {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("public route path must not be blank");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("public route path must start with '/': " + path);
        }
        if (match == null) {
            throw new IllegalArgumentException("public route " + path + " has no match kind");
        }
        methods = normaliseMethods(path, methods);
        deny = deny == null ? Set.of() : Set.copyOf(deny);
        if (!deny.isEmpty() && match != MatchKind.SINGLE_SEGMENT_CHILD) {
            throw new IllegalArgumentException("public route " + path + " sets deny but match kind " + match + " cannot apply it");
        }
        auditUsername = auditUsername == null || auditUsername.isBlank() ? null : auditUsername.trim();
    }

    private static Set<String> normaliseMethods(String path, Set<String> methods) {
        if (methods == null) {
            return Set.of();
        }
        Set<String> upper = new LinkedHashSet<>();
        for (String method : methods) {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("public route " + path + " lists a blank method");
            }
            upper.add(method.trim().toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(upper);
    }

    /**
     * Whether a request is covered by this entry.
     *
     * @param method the request's HTTP method
     * @param requestPath the request path within the application
     * @return true when the method is allowed and the path satisfies {@link #match()}
     */
    public boolean matches(String method, String requestPath) {
        if (!methods.isEmpty() && !methods.contains(method)) {
            return false;
        }
        return match.matches(path, requestPath, deny);
    }

    /** How a configured path is compared against a request path. */
    public enum MatchKind {
        /** The request path equals the configured path. */
        EXACT {
            @Override
            boolean matches(String configured, String requestPath, Set<String> deny) {
                return requestPath.equals(configured);
            }
        },
        /** The request path equals the configured path or continues past it at a {@code /} boundary. */
        PREFIX {
            @Override
            boolean matches(String configured, String requestPath, Set<String> deny) {
                return requestPath.equals(configured) || requestPath.startsWith(configured + "/");
            }
        },
        /** The request path ends with the configured path. */
        SUFFIX {
            @Override
            boolean matches(String configured, String requestPath, Set<String> deny) {
                return requestPath.endsWith(configured);
            }
        },
        /**
         * The request path is the configured path, optionally with a trailing slash, or the configured path followed by exactly one segment
         * (trailing slash optional) that is not in the deny list.
         */
        SINGLE_SEGMENT_CHILD {
            @Override
            boolean matches(String configured, String requestPath, Set<String> deny) {
                if (requestPath.equals(configured) || requestPath.equals(configured + "/")) {
                    return true;
                }
                if (!requestPath.startsWith(configured + "/")) {
                    return false;
                }
                String remainder = requestPath.substring(configured.length() + 1);
                if (remainder.endsWith("/")) {
                    remainder = remainder.substring(0, remainder.length() - 1);
                }
                return !remainder.isEmpty() && !remainder.contains("/") && !deny.contains(remainder);
            }
        };

        abstract boolean matches(String configured, String requestPath, Set<String> deny);
    }
}
