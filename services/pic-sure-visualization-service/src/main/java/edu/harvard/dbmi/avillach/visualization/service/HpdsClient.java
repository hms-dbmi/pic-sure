package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class HpdsClient {

    private final HpdsCallStrategy callStrategy;
    private final UUID authorizedResourceUUID;
    private final UUID openResourceUUID;

    public HpdsClient(
        HpdsCallStrategy callStrategy, @Value("${hpds.resource.authorized.uuid:}") String authorizedUUID,
        @Value("${hpds.resource.open.uuid:}") String openUUID
    ) {
        this.callStrategy = callStrategy;
        this.authorizedResourceUUID = authorizedUUID.isBlank() ? null : UUID.fromString(authorizedUUID);
        this.openResourceUUID = openUUID.isBlank() ? null : UUID.fromString(openUUID);
    }

    public Map<String, Map<String, Integer>> getAuthCrossCounts(Query query, ResultType resultType, String bearerToken) {
        if (authorizedResourceUUID == null) {
            throw new VisualizationException("Authorized HPDS resource UUID (hpds.resource.authorized.uuid) is not configured");
        }
        return callStrategy.queryCrossCounts(
            query, resultType, authorizedResourceUUID, bearerToken, new ParameterizedTypeReference<Map<String, Map<String, Integer>>>() {}
        );
    }

    public Map<String, Map<String, String>> getOpenCrossCounts(Query query, ResultType resultType, String bearerToken) {
        UUID resourceUUID = openResourceUUID != null ? openResourceUUID : authorizedResourceUUID;
        if (resourceUUID == null) {
            throw new VisualizationException(
                "No HPDS resource UUID configured for open access (hpds.resource.open.uuid and hpds.resource.authorized.uuid are both empty)"
            );
        }
        return callStrategy.queryCrossCounts(
            query, resultType, resourceUUID, bearerToken, new ParameterizedTypeReference<Map<String, Map<String, String>>>() {}
        );
    }
}
