package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.ContinuousData;

import java.util.List;
import java.util.Map;

public interface IDataProcessingService {

    List<CategoricalData> getCategoricalData(Map<String, Map<String, Integer>> crossCountsMap);

    List<ContinuousData> getContinuousData(Map<String, Map<String, Integer>> crossCountsMap);

}
