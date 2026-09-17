package edu.harvard.hms.dbmi.avillach.auth.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Config for provisioning/deprovisioning a user's Okta account (via an Okta Workflow webhook) when an
 * admin approves or deactivates them.
 *
 * <p>Enabled only when both {@code OKTA_WORKFLOW_INVOKE_URL} and {@code OKTA_WORKFLOW_CLIENT_TOKEN} are
 * set - a half-configured pair is almost certainly a mistake, so that case fails application startup
 * instead of silently disabling the feature or failing on the first approval. Deprovisioning is a
 * separate opt-in on top of that.</p>
 */
@Component
public class OktaProvisioningConfig {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String invokeUrl;
    private final String clientToken;
    private final boolean deprovisioningEnabled;
    private final String applicationAccess;
    private final String division;
    private final String defaultUserType;

    private boolean enabled;

    @Autowired
    public OktaProvisioningConfig(
        @Value("${okta.workflow.invoke-url}") String invokeUrl, @Value("${okta.workflow.client-token}") String clientToken,
        @Value("${okta.workflow.deprovisioning.enabled}") boolean deprovisioningEnabled,
        @Value("${application.okta.application-access}") String applicationAccess,
        @Value("${application.okta.division}") String division, @Value("${application.okta.default-user-type}") String defaultUserType
    ) {
        this.invokeUrl = invokeUrl;
        this.clientToken = clientToken;
        this.deprovisioningEnabled = deprovisioningEnabled;
        this.applicationAccess = applicationAccess;
        this.division = division;
        this.defaultUserType = defaultUserType;
    }

    @PostConstruct
    public void init() {
        boolean hasUrl = StringUtils.isNotBlank(invokeUrl);
        boolean hasToken = StringUtils.isNotBlank(clientToken);

        if (hasUrl != hasToken) {
            throw new IllegalStateException(
                "OKTA_WORKFLOW_INVOKE_URL and OKTA_WORKFLOW_CLIENT_TOKEN must both be set, or both left unset. "
                    + "Only one was provided."
            );
        }

        this.enabled = hasUrl && hasToken;
        if (enabled) {
            logger.info("Okta provisioning is enabled (deprovisioning enabled: {}).", deprovisioningEnabled);
        } else {
            logger.info("Okta provisioning is disabled (OKTA_WORKFLOW_INVOKE_URL is not set).");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDeprovisioningEnabled() {
        return enabled && deprovisioningEnabled;
    }

    public String getInvokeUrl() {
        return invokeUrl;
    }

    public String getClientToken() {
        return clientToken;
    }

    public String getApplicationAccess() {
        return applicationAccess;
    }

    public String getDivision() {
        return division;
    }

    public String getDefaultUserType() {
        return defaultUserType;
    }
}
