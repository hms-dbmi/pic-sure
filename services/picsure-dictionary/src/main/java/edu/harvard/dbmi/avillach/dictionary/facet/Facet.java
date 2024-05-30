package edu.harvard.dbmi.avillach.dictionary.facet;

import jakarta.annotation.Nullable;

import java.util.List;

public record Facet(
    String name, String display, String description,
    @Nullable Integer count, @Nullable List<Facet> children, String category
) {
}
