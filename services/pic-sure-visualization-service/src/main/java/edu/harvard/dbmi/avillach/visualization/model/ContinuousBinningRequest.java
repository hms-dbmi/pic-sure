package edu.harvard.dbmi.avillach.visualization.model;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ContinuousBinningRequest(@NotNull(message = "Request must contain a 'query' field") Map<String, Map<String, Integer>> query) {
}
