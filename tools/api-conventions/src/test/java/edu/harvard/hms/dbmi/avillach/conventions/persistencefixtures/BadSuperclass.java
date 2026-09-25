package edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures;

import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BadSuperclass {

    protected Status inherited;
}
