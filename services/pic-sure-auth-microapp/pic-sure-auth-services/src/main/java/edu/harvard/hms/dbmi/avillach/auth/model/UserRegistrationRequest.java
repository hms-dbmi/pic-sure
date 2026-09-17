package edu.harvard.hms.dbmi.avillach.auth.model;

/**
 * <p>Request body for public self-registration ({@code POST /user/register}).</p>
 *
 * <p>Deliberately a standalone DTO rather than the {@link edu.harvard.hms.dbmi.avillach.auth.entity.User}
 * entity: this endpoint has no authenticated caller, so nothing it accepts (roles, active, connection,
 * subject) can be allowed to come from the request - only email and the form's free-form metadata are
 * meaningful here. This also sidesteps the client sending {@code acceptedTOS} as a boolean, which does
 * not match the entity's {@code Date} field.</p>
 */
public class UserRegistrationRequest {

    private String email;
    private String generalMetadata;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getGeneralMetadata() {
        return generalMetadata;
    }

    public void setGeneralMetadata(String generalMetadata) {
        this.generalMetadata = generalMetadata;
    }
}
