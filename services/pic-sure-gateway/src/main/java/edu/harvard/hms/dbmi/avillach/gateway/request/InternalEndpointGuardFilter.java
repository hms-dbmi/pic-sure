package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.io.IOException;
import java.util.ArrayList;
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
 * whose first or second path segment is {@code internal} with the structured gateway 404 BEFORE routing or the auth chain can call PSAMA.
 * That covers the top-level {@code /internal/**} and {@code /<route>/internal/**} for every route, at any depth and including the bare
 * prefixes. A service's internal endpoints are service-to-service only, gated by {@code X-PIC-SURE-INTERNAL-TOKEN}. They must not be
 * reachable through the public gateway even with a stolen token. A 404 rather than 403 deliberately does not confirm the endpoint exists.
 *
 * <p>The second segment is guarded for every route, not for a list of them. A route that strips its first segment, as {@code /logging},
 * {@code /dictionary} and {@code /visualization} do, delivers {@code /<route>/internal/x} to its service as {@code /internal/x}, and a
 * route that forwards the path unchanged, as {@code /operations} does, can serve {@code /<route>/internal} itself. Guarding the shape means
 * a route added later is covered without a change here. No public path of any routed service has {@code internal} as its second segment. A
 * deeper segment is not guarded, so {@code /hpds/auth/internal/x} and {@code /dictionary/concepts/internal} pass through.
 *
 * <p>Matching is on the path the backend will resolve, not on the raw request URI: {@link RoutedRequestPath} percent-decodes each segment
 * once and drops {@code ;} matrix parameters, and empty and {@code .} segments are ignored. The path is checked both as written and with
 * its {@code ..} segments resolved. A guarded path written as {@code /dictionary/%69nternal} or {@code /dictionary/x/../internal} is
 * therefore rejected like its literal form. A path that still holds a {@code %} after one decoding, because it was encoded twice or its
 * encoding is invalid, is rejected too, since a later hop could decode it into a guarded path. The comparison is case-sensitive and matches
 * whole segments, so {@code /internalfoo} and {@code /dictionary/internalized} pass through.
 */
public class InternalEndpointGuardFilter extends OncePerRequestFilter {

    private static final String INTERNAL_SEGMENT = "internal";

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
        return decodedPath.contains("%") || startsWithInternal(segments(decodedPath, false))
            || startsWithInternal(segments(decodedPath, true));
    }

    private static boolean startsWithInternal(List<String> segments) {
        return segments.stream().limit(2).anyMatch(INTERNAL_SEGMENT::equals);
    }

    private static List<String> segments(String path, boolean resolveParentSegments) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (resolveParentSegments && "..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                segments.add(segment);
            }
        }
        return segments;
    }
}
