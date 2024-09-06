package edu.harvard.dbmi.avillach.dictionary.facet;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

public record Facet(
    String name, String display, String description, String fullName,
    @Nullable Integer count, @Nullable List<Facet> children, String category,
    @Nullable Map<String, String> meta
) {
    public Facet(Facet core, Map<String, String> meta) {
        this(core.name(), core.display(), core.description(), core.fullName(), core.count(), core.children(), core.category(), meta);
    }

    public Facet(String name, String category) {
        this(name, "", "", "", null, null, category, null);
    }

    public Facet withChildren(List<Facet> children) {
        return new Facet(this.name, this.display, this.description, this.fullName, this.count, children, this.category, this.meta);
    }
}
