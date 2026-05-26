package edu.harvard.dbmi.avillach.visualization.model;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

public record VisualizationQueryRequest(@NotNull(message = "Request must contain a 'query' field") Query query) {
}
