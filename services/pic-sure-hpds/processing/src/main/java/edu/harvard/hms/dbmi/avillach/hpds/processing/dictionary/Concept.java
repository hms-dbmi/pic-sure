package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Concept(String conceptPath, String name, String display, String dataset, String description, Map<String, String> meta) {
}
