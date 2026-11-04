package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A fully documented class that is not a record. It holds a description on a getter, an example on a field
 * whose description is on its getter, a field ignored through its getter, a field hidden through its getter,
 * a transient field and a serialisation constant.
 */
@Schema(description = "An investigator on a study")
public class Investigator extends Person {

    private static final long serialVersionUID = 1L;

    @Schema(description = "The investigator's full name", example = "Ada Lovelace")
    private String name;

    private String institution;

    private boolean active;

    @Schema(example = "0000-0002-1825-0097")
    private String orcid;

    private String reviewerCode;

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

    /** @return the investigator's ORCID iD */
    @Schema(description = "The investigator's ORCID iD")
    public String getOrcid() {
        return orcid;
    }

    /** @return the code reviewers use internally, which the document leaves out */
    @Schema(hidden = true)
    public String getReviewerCode() {
        return reviewerCode;
    }

    /** @return the password hash, which never leaves the service */
    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }
}
