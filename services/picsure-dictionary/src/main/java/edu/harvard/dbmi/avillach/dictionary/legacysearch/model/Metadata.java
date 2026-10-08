package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    description = "Attributes of a concept under the column names the legacy search used. The keys are the same for both concept types, and a continuous concept adds min and max."
)
public sealed interface Metadata permits ContinuousMetadata, CategoricalMetadata {
}
