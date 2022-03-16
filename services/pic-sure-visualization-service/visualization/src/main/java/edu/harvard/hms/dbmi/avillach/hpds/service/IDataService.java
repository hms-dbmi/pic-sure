package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.QueryRequest;

import java.util.List;

public interface IDataService {

    List<CategoricalData> getCategoricalData(QueryRequest queryRequest);

}
