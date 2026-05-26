package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.Map;
import java.util.UUID;

public interface HpdsCallStrategy {

    <T> Map<String, T> queryCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken,
        org.springframework.core.ParameterizedTypeReference<Map<String, T>> typeRef
    );
}
