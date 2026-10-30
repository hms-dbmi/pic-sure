package edu.harvard.hms.dbmi.avillach.gateway.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides which requests take the open-access path, in one place for {@code OpenAccessFilter} and the stage-1 pre-validation throttle, so
 * the throttle counts exactly the requests that would reach PSAMA. A request on a {@link PublicEndpointPolicy} route, or with a login
 * token, is not open access. A bearer PSAMA key ({@link ApiKeyBearer}) is open access, and so is no usable token ({@code Authorization}
 * missing, blank, or at most 7 characters) when open access is enabled. A key presented while open access is disabled is refused without a
 * PSAMA call.
 */
public final class OpenAccessRequests {

    public enum Route {
        /** Not open access: a public endpoint, a login token, or no token with open access disabled. */
        NOT_OPEN_ACCESS,
        /** A key presented while open access is disabled. Denied without a PSAMA call. */
        OPEN_ACCESS_DISABLED,
        /** Sent to PSAMA's open-access validate. */
        VALIDATE
    }

    /** {@code apiKey} is the presented key, or {@code null} when none was presented. */
    public record Classification(Route route, String apiKey) {
    }

    private final boolean openAccessEnabled;
    private final PublicEndpointPolicy publicEndpoints;

    public OpenAccessRequests(boolean openAccessEnabled, PublicEndpointPolicy publicEndpoints) {
        this.openAccessEnabled = openAccessEnabled;
        this.publicEndpoints = publicEndpoints;
    }

    public Classification classify(HttpServletRequest req) {
        if (publicEndpoints.evaluate(req.getMethod(), req.getRequestURI()).publicEndpoint()) {
            return new Classification(Route.NOT_OPEN_ACCESS, null);
        }
        String authz = req.getHeader("Authorization");
        String apiKey = ApiKeyBearer.extract(authz);
        boolean noToken = authz == null || authz.isBlank() || authz.length() <= 7; // Missing or empty Bearer value.
        if (apiKey == null && (!openAccessEnabled || !noToken)) {
            return new Classification(Route.NOT_OPEN_ACCESS, null);
        }
        return new Classification(openAccessEnabled ? Route.VALIDATE : Route.OPEN_ACCESS_DISABLED, apiKey);
    }

    /** True when {@code OpenAccessFilter} would call PSAMA for this request. */
    public boolean validatesWithPsama(HttpServletRequest req) {
        return classify(req).route() == Route.VALIDATE;
    }
}
