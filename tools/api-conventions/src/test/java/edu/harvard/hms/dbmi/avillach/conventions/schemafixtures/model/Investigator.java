package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A fully documented class that is not a record. It holds a description on a getter, a field ignored through
 * its getter, a transient field and a serialisation constant.
 */
@Schema(description = "An investigator on a study")
public class Investigator extends Person {

    private static final long serialVersionUID = 1L;

    @Schema(description = "The investigator's full name", example = "Ada Lovelace")
    private String name;

    private String institution;

    private boolean active;

    private String passwordHash;

    private transient String cachedDisplayName;

    /** @return the full name */
    public String getName() {
        return name;
    }

    /** @return the institution */
    @Schema(description = "The institution the investigator works at", example = "Harvard Medical School")
    public String getInstitution() {
        return institution;
    }

    /** @return whether the investigator is active */
    @Schema(description = "Whether the investigator still works on the study")
    public boolean isActive() {
        return active;
    }

    /** @return the password hash, which never leaves the service */
    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }
}
