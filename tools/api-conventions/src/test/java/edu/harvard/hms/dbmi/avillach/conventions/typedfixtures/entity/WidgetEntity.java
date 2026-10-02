package edu.harvard.hms.dbmi.avillach.conventions.typedfixtures.entity;

import jakarta.persistence.Entity;

/** A JPA entity, which no handler may bind or return. */
@Entity
public class WidgetEntity {

    public String name;
}
