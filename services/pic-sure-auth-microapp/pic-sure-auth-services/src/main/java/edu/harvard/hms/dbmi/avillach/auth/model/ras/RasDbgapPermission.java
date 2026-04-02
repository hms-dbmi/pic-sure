package edu.harvard.hms.dbmi.avillach.auth.model.ras;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * The RasDbgapPermission class is a model for the RAS dbGaP Permission object. An example of this object is: This is not a real study, but
 * an example of what the object looks like. <pre> { "consent_name": "Unrestricted", "phs_id": "phs000123", "version": "v1",
 * "participant_set": "p1", "consent_group": "c1", "role": "pi", "expiration": 1234567890 } </pre>
 */
public class RasDbgapPermission {

    @JsonProperty("consent_name")
    private String consentName;
    @JsonProperty("phs_id")
    private String phsId;
    private String version;
    @JsonProperty("participant_set")
    private String participantSet;
    @JsonProperty("consent_group")
    private String consentGroup;
    private String role;
    private long expiration;

    public String getConsentName() {
        return consentName;
    }

    public RasDbgapPermission setConsentName(String consentName) {
        this.consentName = consentName;
        return this;
    }

    public String getPhsId() {
        return phsId;
    }

    public RasDbgapPermission setPhsId(String phsId) {
        this.phsId = phsId;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public RasDbgapPermission setVersion(String version) {
        this.version = version;
        return this;
    }

    public String getParticipantSet() {
        return participantSet;
    }

    public RasDbgapPermission setParticipantSet(String participantSet) {
        this.participantSet = participantSet;
        return this;
    }

    public String getConsentGroup() {
        return consentGroup;
    }

    public RasDbgapPermission setConsentGroup(String consentGroup) {
        this.consentGroup = consentGroup;
        return this;
    }

    public String getRole() {
        return role;
    }

    public RasDbgapPermission setRole(String role) {
        this.role = role;
        return this;
    }

    public long getExpiration() {
        return expiration;
    }

    public RasDbgapPermission setExpiration(long expiration) {
        this.expiration = expiration;
        return this;
    }

    @Override
    public String toString() {
        return "RasDbgapPermission{" + "consentName='" + consentName + '\'' + ", phsId='" + phsId + '\'' + ", version='" + version + '\''
            + ", participantSet='" + participantSet + '\'' + ", consentGroup='" + consentGroup + '\'' + ", role='" + role + '\''
            + ", expiration=" + expiration + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        RasDbgapPermission that = (RasDbgapPermission) o;
        return expiration == that.expiration && Objects.equals(consentName, that.consentName) && Objects.equals(phsId, that.phsId)
            && Objects.equals(version, that.version) && Objects.equals(participantSet, that.participantSet)
            && Objects.equals(consentGroup, that.consentGroup) && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consentName, phsId, version, participantSet, consentGroup, role, expiration);
    }
}
