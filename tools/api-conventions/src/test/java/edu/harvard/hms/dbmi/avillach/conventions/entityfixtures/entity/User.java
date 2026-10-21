package edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.entity;

import jakarta.persistence.Entity;

/** An entity whose nested display class shares its package but is not itself an entity. */
@Entity
public class User {

    public String email;

    /** A read-only view of a user, which lives beside the entity and carries no JPA annotation. */
    public static class UserForDisplay {
        public String email;
    }
}
