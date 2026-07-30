package edu.harvard.dbmi.avillach.visualization.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * The {@code POST /bin/continuous} request body. Only {@code query} is read; legacy callers additionally send the retired
 * {@code resourceUUID}/{@code resourceCredentials} pair, which is ignored rather than rejected.
 */
// TODO(well-defined-contracts): remove with Task 11 (viz bare Query + binning/info). StrictWebDeserializationConfig now makes every request
// body reject unmodelled properties; this endpoint is retyped to the contracts-module request shape in Task 11, at which point legacy
// clients stop sending resourceUUID/resourceCredentials and this opt-out goes away with it.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContinuousBinningRequest(@NotNull(message = "Request must contain a 'query' field") Map<String, Map<String, Integer>> query) {
}
