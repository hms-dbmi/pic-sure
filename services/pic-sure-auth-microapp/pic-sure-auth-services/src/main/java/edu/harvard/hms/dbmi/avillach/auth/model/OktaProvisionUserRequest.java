package edu.harvard.hms.dbmi.avillach.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p>Request body for the Okta Workflow "invoke" webhook that provisions/deprovisions a user's Okta
 * account. Field names (including the {@code ApplicationAccess} casing) mirror the Workflow's expected
 * payload exactly - this is an external contract, not our naming convention.</p>
 *
 * <p>Several fields are placeholder constants for now (see {@link
 * edu.harvard.hms.dbmi.avillach.auth.config.OktaProvisioningConfig}), pending confirmation from the
 * Okta team of what the Workflow actually needs.</p>
 */
public class OktaProvisionUserRequest {

    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String userType;
    private String division;
    private String requestID;
    private String isActive;
    @JsonProperty("ApplicationAccess")
    private String applicationAccess;
    private String identityProviderName;
    private String projectId;

    public String getUsername() {
        return username;
    }

    public OktaProvisionUserRequest setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getFirstName() {
        return firstName;
    }

    public OktaProvisionUserRequest setFirstName(String firstName) {
        this.firstName = firstName;
        return this;
    }

    public String getLastName() {
        return lastName;
    }

    public OktaProvisionUserRequest setLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public OktaProvisionUserRequest setEmail(String email) {
        this.email = email;
        return this;
    }

    public String getUserType() {
        return userType;
    }

    public OktaProvisionUserRequest setUserType(String userType) {
        this.userType = userType;
        return this;
    }

    public String getDivision() {
        return division;
    }

    public OktaProvisionUserRequest setDivision(String division) {
        this.division = division;
        return this;
    }

    public String getRequestID() {
        return requestID;
    }

    public OktaProvisionUserRequest setRequestID(String requestID) {
        this.requestID = requestID;
        return this;
    }

    public String getIsActive() {
        return isActive;
    }

    public OktaProvisionUserRequest setIsActive(String isActive) {
        this.isActive = isActive;
        return this;
    }

    public String getApplicationAccess() {
        return applicationAccess;
    }

    public OktaProvisionUserRequest setApplicationAccess(String applicationAccess) {
        this.applicationAccess = applicationAccess;
        return this;
    }

    public String getIdentityProviderName() {
        return identityProviderName;
    }

    public OktaProvisionUserRequest setIdentityProviderName(String identityProviderName) {
        this.identityProviderName = identityProviderName;
        return this;
    }

    public String getProjectId() {
        return projectId;
    }

    public OktaProvisionUserRequest setProjectId(String projectId) {
        this.projectId = projectId;
        return this;
    }
}
