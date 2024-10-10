package edu.harvard.dbmi.avillach.dictionary.dataset;

import jakarta.annotation.Nullable;

import java.util.Map;

public record Dataset(String ref, String fullName, String abbreviation, String description, @Nullable Map<String, String> meta) {

    public Dataset(String ref, String fullName, String abbreviation, String description) {
        this(ref, fullName, abbreviation, description, null);
    }

    public Dataset withMeta(Map<String, String> meta) {
        return new Dataset(ref, fullName, abbreviation, description, meta);
    }
}
