package edu.harvard.hms.dbmi.avillach.commons.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds picsure.actuator.* — the inter-service application/monitoring token config. This is NOT a user token; it gates machine-to-machine
 * access to /actuator/**.
 */
@ConfigurationProperties(prefix = "picsure.actuator")
public class ActuatorTokenProperties {

    /** When false, no token check is enforced (AIO dev convenience / production rollback). */
    private boolean requireToken = true;

    /** The value the X-Application-Token header must equal (env: PICSURE_APPLICATION_TOKEN). */
    private String token = "";

    /** Header name. Default X-Application-Token; overridable for testing. */
    private String headerName = "X-Application-Token";

    public boolean isRequireToken() {
        return requireToken;
    }

    public void setRequireToken(boolean requireToken) {
        this.requireToken = requireToken;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
