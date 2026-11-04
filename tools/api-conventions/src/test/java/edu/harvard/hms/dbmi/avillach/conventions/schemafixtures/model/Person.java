package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** A documented superclass. Its fields serialise with every subclass, so the walk reaches it. */
@Schema(description = "A person known to the platform")
public class Person {

    @Schema(description = "The person's email address", example = "researcher@example.org")
    private String email;

    /** @return the email address */
    public String getEmail() {
        return email;
    }
}
