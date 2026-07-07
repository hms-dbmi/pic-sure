package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Result(
    Metadata metadata, List<String> values, @JsonProperty("studyId") String studyId, @JsonProperty("dtId") String dtId,
    @JsonProperty("varId") String varId, @JsonProperty("is_categorical") boolean isCategorical,
    @JsonProperty("is_continuous") boolean isContinuous
) {
}
