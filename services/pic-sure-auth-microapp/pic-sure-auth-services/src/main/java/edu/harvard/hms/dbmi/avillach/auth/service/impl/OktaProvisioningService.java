package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.config.OktaProvisioningConfig;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.IdpProvisioningException;
import edu.harvard.hms.dbmi.avillach.auth.model.OktaProvisionUserRequest;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <p>Calls the Okta Workflow webhook that provisions/deprovisions a user's Okta account, triggered when
 * an admin approves or deactivates a pic-sure user (see {@link UserService#updateUser(List)}).</p>
 *
 * <p>This is a webhook invocation, not Okta's Management API - the Workflow itself (configured in Okta,
 * outside this codebase) does the actual account creation. Callers must treat a thrown {@link
 * IdpProvisioningException} as "the caller's state change should not be persisted" - see {@code
 * UserService}, which calls this before saving so a failure here blocks the local activation/deactivation.</p>
 */
@Service
public class OktaProvisioningService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final RestClientUtil restClientUtil;
    private final OktaProvisioningConfig oktaProvisioningConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public OktaProvisioningService(RestClientUtil restClientUtil, OktaProvisioningConfig oktaProvisioningConfig) {
        this.restClientUtil = restClientUtil;
        this.oktaProvisioningConfig = oktaProvisioningConfig;
    }

    public void provisionUser(User user) {
        invokeWorkflow(user, STATUS_ACTIVE);
    }

    public void deprovisionUser(User user) {
        invokeWorkflow(user, STATUS_INACTIVE);
    }

    private void invokeWorkflow(User user, String status) {
        OktaProvisionUserRequest request = buildRequest(user, status);
        String uri = oktaProvisioningConfig.getInvokeUrl() + "?clientToken="
            + URLEncoder.encode(oktaProvisioningConfig.getClientToken(), StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        // Deliberately no automatic retry: on failure the caller's state change is not persisted (see
        // UserService), so the admin can just retry the same approve/deactivate action.
        try {
            String body = objectMapper.writeValueAsString(request);
            ResponseEntity<String> response =
                restClientUtil.retrievePostResponseWithRequestConfiguration(uri, headers, body, REQUEST_TIMEOUT_MS);
            logger.info(
                "Okta workflow invoked for user {} (status={}), response status: {}", user.getUuid(), status,
                response.getStatusCode()
            );
        } catch (JsonProcessingException e) {
            // Never log `request` here - do not want to risk the clientToken/uri leaking via a toString().
            throw new IdpProvisioningException("Failed to build Okta provisioning request for user " + user.getUuid(), e);
        } catch (Exception e) {
            logger.error("Okta workflow invocation failed for user {} (status={}): {}", user.getUuid(), status, e.getMessage());
            throw new IdpProvisioningException("Failed to " + (status.equals(STATUS_ACTIVE) ? "provision" : "deprovision")
                + " user " + user.getUuid() + " in Okta", e);
        }
    }

    private OktaProvisionUserRequest buildRequest(User user, String status) {
        String[] name = parseNameFromMetadata(user.getGeneralMetadata());
        return new OktaProvisionUserRequest().setUsername(generateUsername(user)).setFirstName(name[0]).setLastName(name[1])
            .setEmail(user.getEmail()).setUserType(oktaProvisioningConfig.getDefaultUserType())
            .setDivision(oktaProvisioningConfig.getDivision()).setRequestID(user.getUuid().toString()).setIsActive(status)
            .setApplicationAccess(oktaProvisioningConfig.getApplicationAccess())
            .setIdentityProviderName(user.getConnection() != null ? user.getConnection().getLabel() : null)
            // The reference implementation sourced division and projectId from the same value - kept
            // that way here until the Okta team confirms whether they're actually distinct.
            .setProjectId(oktaProvisioningConfig.getDivision());
    }

    /**
     * Placeholder uniqueness scheme (email local-part + first 4 chars of the user's uuid), pending a
     * real username policy.
     */
    private String generateUsername(User user) {
        String email = user.getEmail();
        String localPart = (email != null && email.contains("@")) ? email.substring(0, email.indexOf('@')) : email;
        String uuidPrefix = user.getUuid().toString().substring(0, 4);
        return localPart + "-" + uuidPrefix;
    }

    /**
     * firstName/lastName aren't stored as their own columns - they only exist inside the register
     * form's free-form generalMetadata blob (by convention, under the "firstName"/"lastName" keys - see
     * defaultRegisterFormFields on the frontend). Not fatal if missing/unparseable: these are only used
     * for identification in Okta.
     */
    private String[] parseNameFromMetadata(String generalMetadata) {
        String firstName = "";
        String lastName = "";
        if (StringUtils.isNotBlank(generalMetadata)) {
            try {
                Map<String, String> metadata = objectMapper.readValue(generalMetadata, new TypeReference<Map<String, String>>() {});
                firstName = metadata.getOrDefault("firstName", "");
                lastName = metadata.getOrDefault("lastName", "");
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse firstName/lastName from generalMetadata for Okta provisioning: {}", e.getMessage());
            }
        }
        return new String[] {firstName, lastName};
    }
}
