package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;

import java.util.Map;

public interface IHpdsService {
    Map<String, Map<String, Integer>> getCrossCountsMap(QueryRequest queryRequest, ResultType resultType);
}
