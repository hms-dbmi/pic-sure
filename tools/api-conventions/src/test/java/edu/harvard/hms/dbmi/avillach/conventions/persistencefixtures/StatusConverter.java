package edu.harvard.hms.dbmi.avillach.conventions.persistencefixtures;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class StatusConverter implements AttributeConverter<Status, String> {

    @Override
    public String convertToDatabaseColumn(Status status) {
        return status == null ? null : status.name();
    }

    @Override
    public Status convertToEntityAttribute(String name) {
        return name == null ? null : Status.valueOf(name);
    }
}
