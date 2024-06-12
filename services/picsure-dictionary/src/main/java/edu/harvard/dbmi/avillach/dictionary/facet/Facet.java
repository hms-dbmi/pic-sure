package edu.harvard.dbmi.avillach.dictionary.facet;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

public record Facet(
    String name, String display, String description,
    @Nullable Integer count, @Nullable List<Facet> children, String category,
    @Nullable Map<String, String> meta
) {
    public Facet(Facet core, Map<String, String> meta) {
        this(core.name(), core.display(), core.description(), core.count(), core.children(), core.category(), meta);
    }
}
