package edu.harvard.hms.dbmi.avillach.pheno.data;

import java.util.List;
import java.util.Map;

import edu.harvard.hms.dbmi.avillach.pheno.data.Filter.FloatFilter;

public class Query {
	public List<String> fields;
	public List<String> requiredFields;
	public Map<String, FloatFilter> numericFilters;
	public Map<String, String[]> categoryFilters;
	public String id;
}
