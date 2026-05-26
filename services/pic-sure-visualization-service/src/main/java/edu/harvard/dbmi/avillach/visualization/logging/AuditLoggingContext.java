package edu.harvard.dbmi.avillach.visualization.logging;

import edu.harvard.dbmi.avillach.visualization.model.CategoricalDistributionData;
import edu.harvard.dbmi.avillach.visualization.model.ContinuousDistributionData;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditLoggingContext {

    public static final String START_TIME_ATTR = "vizStartTime";
    public static final String REQUEST_ID_ATTR = "vizRequestId";
    public static final String METADATA_ATTR = "vizAuditMetadata";
    public static final String ACCESS_TYPE_ATTR = "vizAccessType";
    public static final String AUTHORIZATION_ATTR = "vizAuthorization";
    public static final String SESSION_ID_ATTR = "vizSessionId";

    private AuditLoggingContext() {}

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTR);
        return value != null ? value.toString() : null;
    }

    public static void addMetadata(HttpServletRequest request, String key, Object value) {
        if (value != null) {
            metadata(request).put(key, value);
        }
    }

    public static void addDistributionRequestMetadata(
        HttpServletRequest request, UUID resourceUUID, String accessType, Query query, int subQueryCount
    ) {
        addMetadata(request, "route", "distributions");
        addMetadata(request, "resource_uuid", resourceUUID.toString());
        addMetadata(request, "access_type", accessType);
        List<String> selectedConceptPaths = selectedConceptPaths(query);
        addMetadata(request, "selected_concept_paths", selectedConceptPaths);
        addMetadata(request, "selected_concept_count", selectedConceptPaths.size());
        addMetadata(request, "subquery_count", subQueryCount);
        request.setAttribute(ACCESS_TYPE_ATTR, accessType);
    }

    public static void addDistributionResponseMetadata(HttpServletRequest request, VisualizationResponse response) {
        addMetadata(request, "categorical_series_count", response.categoricalData().size());
        addMetadata(request, "continuous_series_count", response.continuousData().size());
        addMetadata(
            request, "categorical_series_titles", response.categoricalData().stream().map(CategoricalDistributionData::title).toList()
        );
        addMetadata(
            request, "continuous_series_titles", response.continuousData().stream().map(ContinuousDistributionData::title).toList()
        );
        addMetadata(
            request, "categorical_point_count", response.categoricalData().stream().mapToInt(data -> data.categoricalMap().size()).sum()
        );
        addMetadata(
            request, "continuous_point_count", response.continuousData().stream().mapToInt(data -> data.continuousMap().size()).sum()
        );
        addMetadata(
            request, "categorical_obfuscated", response.categoricalData().stream().anyMatch(CategoricalDistributionData::obfuscated)
        );
        addMetadata(request, "continuous_obfuscated", response.continuousData().stream().anyMatch(ContinuousDistributionData::obfuscated));
    }

    public static void addBinningRequestMetadata(HttpServletRequest request, Map<String, Map<String, Integer>> input) {
        addMetadata(request, "route", "bin_continuous");
        addMetadata(request, "input_variable_count", input.size());
        addMetadata(request, "input_point_count", pointCount(input));
        addMetadata(request, "selected_concept_paths", new ArrayList<>(input.keySet()));
    }

    public static void addBinningResponseMetadata(HttpServletRequest request, Map<String, Map<String, Integer>> output) {
        addMetadata(request, "output_variable_count", output.size());
        addMetadata(request, "output_point_count", pointCount(output));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> metadata(HttpServletRequest request) {
        Object value = request.getAttribute(METADATA_ATTR);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        request.setAttribute(METADATA_ATTR, metadata);
        return metadata;
    }

    static List<String> selectedConceptPaths(Query query) {
        if (query == null) {
            return List.of();
        }
        if (query.select() != null && !query.select().isEmpty()) {
            return new ArrayList<>(query.select());
        }
        return query.allFilters().stream().map(filter -> filter.conceptPath()).distinct().toList();
    }

    private static int pointCount(Map<String, Map<String, Integer>> data) {
        return data.values().stream().mapToInt(Map::size).sum();
    }
}
