package edu.harvard.hms.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.visualization.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.visualization.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.visualization.model.domain.QueryRequest;

import java.util.List;
import java.util.Map;

public interface IDataProcessingService {

    List<CategoricalData> getCategoricalData(QueryRequest queryRequest, Map<String, Map<String, Integer>> crossCountsMap);

    List<ContinuousData> getContinuousData(QueryRequest queryRequest, Map<String, Map<String, Integer>> crossCountsMap);

}
