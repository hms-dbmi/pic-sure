package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class HpdsClient {

    private final HpdsCallStrategy callStrategy;

    public HpdsClient(HpdsCallStrategy callStrategy) {
        this.callStrategy = callStrategy;
    }

    public Map<String, Map<String, Integer>> getAuthCrossCounts(Query query, ResultType resultType, UUID resourceUUID, String bearerToken) {
        validateResourceUUID(resourceUUID);
        return callStrategy.queryCrossCounts(
            query, resultType, resourceUUID, bearerToken, new ParameterizedTypeReference<Map<String, Map<String, Integer>>>() {}
        );
    }

    public Map<String, Map<String, String>> getOpenCrossCounts(Query query, ResultType resultType, UUID resourceUUID, String bearerToken) {
        validateResourceUUID(resourceUUID);
        return callStrategy.queryCrossCounts(
            query, resultType, resourceUUID, bearerToken, new ParameterizedTypeReference<Map<String, Map<String, String>>>() {}
        );
    }

    private static void validateResourceUUID(UUID resourceUUID) {
        if (resourceUUID == null) {
            throw new VisualizationException("HPDS resource UUID is required");
        }
    }
}
