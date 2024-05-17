package edu.harvard.dbmi.avillach.dictionary.facet;

import jakarta.annotation.Nullable;

public record Facet(
    String name, String display, String description,
    @Nullable Facet parent, String category
) {
}
