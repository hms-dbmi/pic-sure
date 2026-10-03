package edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;

@Entity
public class GoodEntity {

    static Status fallback = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    private Status byName;

    @Convert(converter = StatusConverter.class)
    private Status converted;

    private transient Status cached;

    @Transient
    private Status derived;

    private String name;
}
