package edu.harvard.hms.dbmi.avillach.query.hpds;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/**
 * Maps the ingress {@code {backend}} path segment ("auth" or "open") to the HPDS call target: the backend's absolute base URL and its
 * service token. v3 endpoints append {@code /v3} to the base URL. Query-lifecycle calls use {@code target.token()} for Bearer
 * authentication; search and values calls use only {@code target.baseUrl()} and send no token.
 */
@Component
public class HpdsBackendSelector {

    public static final String AUTH = "auth";
    public static final String OPEN = "open";

    private final HpdsProperties props;

    public HpdsBackendSelector(HpdsProperties props) {
        this.props = props;
    }

    /** The HPDS call target: the base URL (with {@code /v3} appended for v3) and the per-backend service token. */
    public record HpdsTarget(String baseUrl, String token) {
    }

    /**
     * @param backend the ingress segment: "auth" or "open"
     * @param v3 whether the target endpoint is v3 (append "/v3" to the base)
     * @return the HPDS target (URL + service token) for that backend
     */
    public HpdsTarget select(String backend, boolean v3) {
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
        return new HpdsTarget(v3 ? base + "/v3" : base, token);
    }
}
