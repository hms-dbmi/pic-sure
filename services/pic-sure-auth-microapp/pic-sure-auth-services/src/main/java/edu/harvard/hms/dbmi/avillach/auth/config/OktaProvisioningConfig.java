package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
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
 * <p>Enabled only when {@code OKTA_WORKFLOW_INVOKE_URL}, {@code OKTA_WORKFLOW_CLIENT_TOKEN}, and {@code
 * OKTA_WORKFLOW_CONNECTION_ID} are all set - a half-configured set is almost certainly a mistake, so
 * that case fails application startup instead of silently disabling the feature or failing on the first
 * approval. {@code connection-id} is deliberately its own property rather than defaulting from {@code
 * SELF_REGISTRATION_CONNECTION_ID} - they answer different questions (which connection self-registration
 * joins vs. which connection this Okta Workflow applies to) and shouldn't be silently coupled.
 * Deprovisioning is a separate opt-in on top of all of that.</p>
 */
@Component
public class OktaProvisioningConfig {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ConnectionRepository connectionRepository;
    private final String invokeUrl;
    private final String clientToken;
    private final String connectionId;
    private final boolean deprovisioningEnabled;
    private final String applicationAccess;
    private final String division;
    private final String defaultUserType;

    private boolean enabled;
    private Connection connection;

    @Autowired
    public OktaProvisioningConfig(
        ConnectionRepository connectionRepository, @Value("${okta.workflow.invoke-url}") String invokeUrl,
        @Value("${okta.workflow.client-token}") String clientToken, @Value("${okta.workflow.connection-id}") String connectionId,
        @Value("${okta.workflow.deprovisioning.enabled}") boolean deprovisioningEnabled,
        @Value("${application.okta.application-access}") String applicationAccess,
        @Value("${application.okta.division}") String division, @Value("${application.okta.default-user-type}") String defaultUserType
    ) {
        this.connectionRepository = connectionRepository;
        this.invokeUrl = invokeUrl;
        this.clientToken = clientToken;
        this.connectionId = connectionId;
        this.deprovisioningEnabled = deprovisioningEnabled;
        this.applicationAccess = applicationAccess;
        this.division = division;
        this.defaultUserType = defaultUserType;
    }

    @PostConstruct
    public void init() {
        boolean hasUrl = StringUtils.isNotBlank(invokeUrl);
        boolean hasToken = StringUtils.isNotBlank(clientToken);
        boolean hasConnectionId = StringUtils.isNotBlank(connectionId);

        if (hasUrl != hasToken || hasUrl != hasConnectionId) {
            throw new IllegalStateException(
                "OKTA_WORKFLOW_INVOKE_URL, OKTA_WORKFLOW_CLIENT_TOKEN, and OKTA_WORKFLOW_CONNECTION_ID must all be set, "
                    + "or all left unset. Only some were provided."
            );
        }

        this.enabled = hasUrl && hasToken && hasConnectionId;
        if (enabled) {
            this.connection = connectionRepository.findById(connectionId)
                .orElseThrow(
                    () -> new IllegalStateException(
                        "OKTA_WORKFLOW_CONNECTION_ID=" + connectionId + " does not match any known connection."
                    )
                );
            logger.info(
                "Okta provisioning is enabled for connection {} (deprovisioning enabled: {}).", connectionId, deprovisioningEnabled
            );
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

    public Connection getConnection() {
        return connection;
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
