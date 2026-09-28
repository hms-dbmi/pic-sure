package edu.harvard.hms.dbmi.avillach.query.hpds;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/**
 * Maps the ingress {@code {backend}} path segment ("auth" or "open") to the HPDS call target: the backend's absolute base URL and its
 * service token. Every target appends {@code /v3} to the configured base URL, which is where HPDS serves its query API. Query-lifecycle
 * calls use {@code target.token()} for Bearer authentication; search and values calls use only {@code target.baseUrl()} and send no token.
 */
@Component
public class HpdsBackendSelector {

    public static final String AUTH = "auth";
    public static final String OPEN = "open";

    private final HpdsProperties props;

    public HpdsBackendSelector(HpdsProperties props) {
        this.props = props;
    }

    /** The HPDS call target: the base URL (with {@code /v3} appended) and the per-backend service token. */
    public record HpdsTarget(String baseUrl, String token) {
    }

    /**
     * @param backend the ingress segment: "auth" or "open"
     * @return the HPDS target for that backend: its configured URL with {@code /v3} appended, and its service token
     * @throws PicsureException 400 if the segment is neither "auth" nor "open"; 503 if that backend has no URL configured, which is how a
     *         stack built without an open HPDS instance presents (an unset {@code HPDS_OPEN_URL} binds to the empty string)
     */
    public HpdsTarget select(String backend) {
        String base;
        String token;
        switch (backend == null ? "" : backend) {
            case AUTH -> {
                base = props.getAuthUrl();
                token = props.getAuthToken();
            }
            case OPEN -> {
                base = props.getOpenUrl();
                token = props.getOpenToken();
            }
            default -> throw new PicsureException(
                HttpStatus.BAD_REQUEST, "bad_request", "Unknown HPDS backend '" + backend + "' (expected 'auth' or 'open')"
            );
        }
        if (base == null || base.isBlank()) {
            throw new PicsureException(
                HttpStatus.SERVICE_UNAVAILABLE, "backend_not_configured",
                "HPDS backend '" + backend + "' is not configured in this deployment"
            );
        }
        return new HpdsTarget(base + "/v3", token);
    }
}
