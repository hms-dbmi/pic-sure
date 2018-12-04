package edu.harvard.hms.dbmi.avillach.pheno.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.harvard.hms.dbmi.avillach.pheno.data.Filter.FloatFilter;

public class Query {
	public ResultType expectedResultType = ResultType.DATAFRAME;
	public List<String> fields = new ArrayList<String>();
	public List<String> requiredFields;
	public Map<String, FloatFilter> numericFilters;
	public Map<String, String[]> categoryFilters;
	public String id;
}
