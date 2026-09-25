package edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Entity
public class BadEntity {

    private Status bare;

    @Enumerated
    private Status defaulted;

    @Enumerated(EnumType.ORDINAL)
    private Status ordinal;

    @Convert(disableConversion = true)
    private Status unconverted;

    private String name;
}
