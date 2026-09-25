package edu.harvard.hms.dbmi.avillach.auth.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpMethod;

/**
 * One route PSAMA serves without a token, bound from an entry of {@code security.public-routes.shipped} or
 * {@code security.public-routes.additional}.
 *
 * @param pattern a Spring Security request matcher pattern, such as {@code /tos/latest} or {@code /authentication/**}; must start with
 *        {@code /}
 * @param methods HTTP methods the entry applies to, upper-cased on binding; empty means every method
 * @throws IllegalArgumentException on a blank pattern, a pattern without a leading slash, a blank method, or a method that is not a
 *         standard HTTP method
 */
public record PublicRoute(String pattern, Set<String> methods) {

    private static final Set<String> STANDARD_METHODS = Set.copyOf(Arrays.stream(HttpMethod.values()).map(HttpMethod::name).toList());

    public PublicRoute {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("public route pattern must not be blank");
        }
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException("public route pattern must start with '/': " + pattern);
        }
        methods = normaliseMethods(pattern, methods);
    }

    private static Set<String> normaliseMethods(String pattern, Set<String> methods) {
        if (methods == null) {
            return Set.of();
        }
        Set<String> upper = new LinkedHashSet<>();
        for (String method : methods) {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("public route " + pattern + " lists a blank method");
            }
            String normalised = method.trim().toUpperCase(Locale.ROOT);
            if (!STANDARD_METHODS.contains(normalised)) {
                throw new IllegalArgumentException("public route " + pattern + " lists unknown HTTP method " + method);
            }
            upper.add(normalised);
        }
        return Collections.unmodifiableSet(upper);
    }
}
