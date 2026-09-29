package edu.harvard.hms.dbmi.avillach.commons.request;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.ServletRequestPathUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the request path the way Spring's path-pattern routing matches it, so a filter that decides by path agrees with the route the
 * request is sent to. Spring Cloud Gateway Server WebMVC path predicates test {@code requestPath().pathWithinApplication()} with a
 * {@code PathPattern}, and a {@code PathPattern} compares each segment's {@link PathContainer.PathSegment#valueToMatch()}: the segment
 * percent-decoded exactly once, with any {@code ;} matrix parameters removed. This class builds the same value from the same parser.
 */
public final class RoutedRequestPath {

    private RoutedRequestPath() {}

    /**
     * Returns the decoded path within the application that path-pattern routing matches on. {@code /%6Dcp} resolves to {@code /mcp}, and
     * {@code /%256Dcp} resolves to {@code /%6Dcp}, because each segment is decoded once and never again.
     *
     * @param request the current request
     * @return the decoded path within the application, the raw request URI when it holds an invalid percent-encoding, or null when the
     *         request has no URI
     */
    public static String of(HttpServletRequest request) {
        String rawUri = request.getRequestURI();
        if (rawUri == null) {
            return null;
        }
        try {
            return decodedValue(ServletRequestPathUtils.parse(request).pathWithinApplication());
        } catch (IllegalArgumentException invalidEncoding) {
            return rawUri;
        }
    }

    private static String decodedValue(PathContainer path) {
        StringBuilder decoded = new StringBuilder();
        for (PathContainer.Element element : path.elements()) {
            decoded.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        return decoded.toString();
    }
}
