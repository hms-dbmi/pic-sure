package edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures;

import jakarta.persistence.Entity;

@Entity
public class ForeignEnumEntity {

    private ForeignType foreign;
}
