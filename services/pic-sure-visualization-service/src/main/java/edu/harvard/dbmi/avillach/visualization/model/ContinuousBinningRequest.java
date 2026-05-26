package edu.harvard.dbmi.avillach.visualization.model;

import jakarta.validation.constraints.NotNull;

public record ContinuousBinningRequest(@NotNull(message = "Request must contain a 'query' field") Object query) {
}
