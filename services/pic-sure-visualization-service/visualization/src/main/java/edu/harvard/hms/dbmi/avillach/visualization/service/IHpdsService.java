package edu.harvard.hms.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.visualization.model.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.visualization.model.domain.ResultType;

import java.util.Map;

public interface IHpdsService {

    Map<String, Map<String, Integer>> getCrossCountsMap(QueryRequest queryRequest, ResultType resultType);
}
