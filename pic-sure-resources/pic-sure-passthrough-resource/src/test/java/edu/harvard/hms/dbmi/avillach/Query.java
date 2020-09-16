package edu.harvard.hms.dbmi.avillach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.harvard.hms.dbmi.avillach.Filter.DoubleFilter;
import edu.harvard.hms.dbmi.avillach.Filter.FloatFilter;

public class Query {

	public Query() {
	}

	public Query(Query query) {
		this.expectedResultType = query.expectedResultType;
		this.crossCountFields = new ArrayList<String>(query.crossCountFields);
		this.fields = new ArrayList<String>(query.fields);
		this.requiredFields = new ArrayList<String>(query.requiredFields);
		this.anyRecordOf = new ArrayList<String>(query.anyRecordOf);
		this.numericFilters = new TreeMap<String, DoubleFilter>(query.numericFilters);
		this.categoryFilters = new TreeMap<String, String[]>(query.categoryFilters);
		this.variantInfoFilters = new ArrayList<VariantInfoFilter>();
		if (query.variantInfoFilters != null) {
			query.variantInfoFilters.forEach((filter) -> {
				this.variantInfoFilters.add(new VariantInfoFilter(filter));
			});
		}
		this.id = query.id;
	}

	public ResultType expectedResultType = ResultType.COUNT;
	public List<String> crossCountFields = new ArrayList<String>();
	public List<String> fields = new ArrayList<String>();
	public List<String> requiredFields;
	public List<String> anyRecordOf;
	public Map<String, DoubleFilter> numericFilters;
	public Map<String, String[]> categoryFilters;
	public List<VariantInfoFilter> variantInfoFilters;
	public String id;

	public static class VariantInfoFilter {
		public VariantInfoFilter() {
		}

		public VariantInfoFilter(VariantInfoFilter filter) {
			this.numericVariantInfoFilters = new TreeMap<String, FloatFilter>(filter.numericVariantInfoFilters);
			this.categoryVariantInfoFilters = new TreeMap<String, String[]>(filter.categoryVariantInfoFilters);
		}

		public Map<String, FloatFilter> numericVariantInfoFilters;
		public Map<String, String[]> categoryVariantInfoFilters;
	}
}
