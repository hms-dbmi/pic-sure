package edu.harvard.hms.dbmi.avillach.hpds.data.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.DoubleFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;

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

	public ResultType expectedResultType = ResultType.DATAFRAME;
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
		
		public String toString() {
			StringBuilder builder = new StringBuilder();
			writePartFormat("Numeric Variant Info Filters", numericVariantInfoFilters, builder);
			writePartFormat("Category Variant Info Filters", categoryVariantInfoFilters, builder);
			return builder.toString();
		}
	}

	/**
	 * Some of these query objects can be enormous.  We want to condense them to a readable form.
	 */
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Query of type " + expectedResultType);
		if(id != null) {
			builder.append(" with ID " + id + "\n");
		} else {
			builder.append(" (no ID assigned)\n");
		}
		//We want to show different data based on the query type
		switch(expectedResultType) {
		case INFO_COLUMN_LISTING:
			//info column listing has no query body
			break;
		
		case CROSS_COUNT:
		case OBSERVATION_COUNT:
			writePartFormat("Cross Count Fields", crossCountFields, builder);
		case DATAFRAME:
		case DATAFRAME_MERGED:
			writePartFormat("Data Export Fields", fields, builder);
		case COUNT:
			writePartFormat("Required Fields", requiredFields, builder);
			writePartFormat("Numeric filters", numericFilters, builder);
			writePartFormat("Category filters", categoryFilters, builder);
			writePartFormat("Variant Info filters", variantInfoFilters, builder);
			writePartFormat("Any-Record-Of filters", anyRecordOf, builder);
			
		default:
			//no logic here; all enum values should be present above
			System.out.println("Foratting not supported for type " + expectedResultType);
		}

		return builder.toString();
	}
	
	/**
	 * For some elements of the query, we will iterate over the list of items and send them each to the string builder
	 * @param queryPart
	 * @param varList
	 * @param builder
	 */
	private static void writePartFormat(String queryPart, Collection varList, StringBuilder builder) {
		if(varList == null || varList.isEmpty()) {
			return;
		}
		if(varList.size() > 5) {
			builder.append(varList.size() + " " + queryPart + "\n");
		}else {
			builder.append(queryPart + ": [");  
				for(Object val : varList) {
					if(val instanceof String) {
						String strVal = (String)val;
						for(String part : strVal.split("\\\\")) {
							if(part.length() > 13) {
								builder.append(part.substring(0, 5) + "..." + part.substring(part.length() - 5) + "\\");
							} else {
								builder.append(part + "\\");
							}
						}
						builder.append(", ");
					} else {
						builder.append(val + ", ");
					}
				}
			builder.append("]\n");
		}
	}
	
	/**
	 * For other items that are mapped (e.g., 'variable -> range') we want to show both the name and the values requested (unless truncating)
	 * We can't combine this with the List/Collection method, as the two classes are not compatible (even though the method names are the same)
	 * @param queryPart
	 * @param varMap
	 * @param builder
	 */
	private static void writePartFormat(String queryPart, Map varMap, StringBuilder builder) {
		if(varMap == null || varMap.isEmpty()) {
			return;
		}
		if(varMap.size() > 5) {
			builder.append(varMap.size() + " " + queryPart + "\n");
		}else {
			builder.append(queryPart + ": [");  
				for(Object key : varMap.keySet()) {
					builder.append(key + ": ");
					
					Object value = varMap.get(key);
					if(value instanceof Object[]) {
						builder.append("{");
						for(Object val : (Object[])value) {
							builder.append(val + ", ");
						}
						builder.append("}, ");
					} else {
						builder.append(value + ", ");
					}
				}
			builder.append("]\n");
		}
	}
}
