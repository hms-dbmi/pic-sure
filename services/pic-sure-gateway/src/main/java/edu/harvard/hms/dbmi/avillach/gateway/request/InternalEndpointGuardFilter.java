package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Edge guard, registered UNCONDITIONALLY before the DB-free authentication filters (see {@code ObservabilityConfig}): rejects any request
 * targeting {@code /operations/internal/**}, {@code /dictionary/internal/**} or the top-level {@code /internal/**} (any depth, including
 * the bare prefixes) with the structured gateway 404 BEFORE routing or the auth chain can call PSAMA. Those endpoints are
 * service-to-service only, gated by {@code X-PIC-SURE-INTERNAL-TOKEN}. They must not be reachable through the public gateway even with a
 * stolen token. A 404 rather than 403 deliberately does not confirm the endpoint exists.
 *
 * <p>Matching is on the path the backend will resolve, not on the raw request URI: {@link RoutedRequestPath} percent-decodes each segment
 * once and drops {@code ;} matrix parameters, and empty, {@code .} and {@code ..} segments are then resolved. A guarded prefix written as
 * {@code /dictionary/%69nternal} or {@code /dictionary/x/../internal} is therefore rejected like its literal form. A path that still holds
 * a {@code %} after one decoding, because it was encoded twice or its encoding is invalid, is rejected too, since a later hop could decode
 * it into a guarded path. The comparison is case-sensitive and segment-safe, so {@code /internalfoo} and {@code /dictionary/internalized}
 * pass through.
 */
public class InternalEndpointGuardFilter extends OncePerRequestFilter {

    private static final List<String> GUARDED_PREFIXES = List.of("/operations/internal", "/dictionary/internal", "/internal");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        if (isGuarded(RoutedRequestPath.of(req))) {
            GatewayErrors.write(resp, HttpStatus.NOT_FOUND, "not_found", "Not found.");
            return;
        }
        chain.doFilter(req, resp);
    }

    private static boolean isGuarded(String decodedPath) {
        if (decodedPath == null) {
            return false;
        }
        return decodedPath.contains("%") || isUnderGuardedPrefix(decodedPath) || isUnderGuardedPrefix(resolveDotSegments(decodedPath));
    }

    private static boolean isUnderGuardedPrefix(String path) {
        return GUARDED_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static String resolveDotSegments(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                segments.pollLast();
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }
}
