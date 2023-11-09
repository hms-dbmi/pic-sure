package edu.harvard.dbmi.avillach.dataupload.hpds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class Query {

	private static final Logger log = LoggerFactory.getLogger(Query.class);

	public Query() {

	}

	public Query(Query query) {
		this.expectedResultType = query.expectedResultType;
		this.crossCountFields = new ArrayList<String>(query.crossCountFields);
		this.fields = new ArrayList<String>(query.fields);
		this.requiredFields = new ArrayList<String>(query.requiredFields);
		this.anyRecordOf = new ArrayList<String>(query.anyRecordOf);
		this.numericFilters = new TreeMap<String, Filter.DoubleFilter>(query.numericFilters);
		this.categoryFilters = new TreeMap<String, String[]>(query.categoryFilters);
		this.variantInfoFilters = new ArrayList<VariantInfoFilter>();
		if (query.variantInfoFilters != null) {
			query.variantInfoFilters.forEach((filter) -> {
				this.variantInfoFilters.add(new VariantInfoFilter(filter));
			});
		}
		this.id = query.id;
	}

	private ResultType expectedResultType = ResultType.COUNT;
	private List<String> crossCountFields = new ArrayList<>();
	private List<String> fields = new ArrayList<>();
	private List<String> requiredFields = new ArrayList<>();
	private List<String> anyRecordOf = new ArrayList<>();
	private List<List<String>> anyRecordOfMulti = new ArrayList<>();
	private Map<String, Filter.DoubleFilter> numericFilters = new HashMap<>();
	private Map<String, String[]> categoryFilters = new HashMap<>();
	private List<VariantInfoFilter> variantInfoFilters = new ArrayList<>();
	private String id;


	public ResultType getExpectedResultType() {
		return expectedResultType;
	}

	public List<String> getCrossCountFields() {
		return crossCountFields;
	}

	public List<String> getFields() {
		return fields;
	}

	public List<String> getRequiredFields() {
		return requiredFields;
	}

	public List<String> getAnyRecordOf() {
		return anyRecordOf;
	}
	public List<List<String>> getAnyRecordOfMulti() {
		return anyRecordOfMulti;
	}
	public List<List<String>> getAllAnyRecordOf() {
		List<List<String>> anyRecordOfMultiCopy = new ArrayList<>(anyRecordOfMulti);
		anyRecordOfMultiCopy.add(anyRecordOf);
		return anyRecordOfMultiCopy;
	}

	public Map<String, Filter.DoubleFilter> getNumericFilters() {
		return numericFilters;
	}

	public Map<String, String[]> getCategoryFilters() {
		return categoryFilters;
	}

	public List<VariantInfoFilter> getVariantInfoFilters() {
		return variantInfoFilters;
	}

	public String getId() {
		return id;
	}

	public void setExpectedResultType(ResultType expectedResultType) {
		this.expectedResultType = expectedResultType;
	}

	public void setCrossCountFields(Collection<String> crossCountFields) {
		this.crossCountFields = crossCountFields != null ? new ArrayList<>(crossCountFields) : new ArrayList<>();
	}

	public void setFields(Collection<String> fields) {
		this.fields = fields != null ? new ArrayList<>(fields) : new ArrayList<>();
	}

	public void setRequiredFields(Collection<String> requiredFields) {
		this.requiredFields = requiredFields!= null ? new ArrayList<>(requiredFields) : new ArrayList<>();
	}

	public void setAnyRecordOf(Collection<String> anyRecordOf) {
		this.anyRecordOf = anyRecordOf != null ? new ArrayList<>(anyRecordOf) : new ArrayList<>();
	}
	public void setAnyRecordOfMulti(Collection<List<String>> anyRecordOfMulti) {
		this.anyRecordOfMulti = anyRecordOfMulti != null ? new ArrayList<>(anyRecordOfMulti) : new ArrayList<>();
	}

	public void setNumericFilters(Map<String, Filter.DoubleFilter> numericFilters) {
		this.numericFilters = numericFilters != null ? new HashMap<>(numericFilters) : new HashMap<>();
	}

	public void setCategoryFilters(Map<String, String[]> categoryFilters) {
		this.categoryFilters = categoryFilters != null ? new HashMap<>(categoryFilters) : new HashMap<>();
	}

	public void setVariantInfoFilters(Collection<VariantInfoFilter> variantInfoFilters) {
		this.variantInfoFilters = variantInfoFilters != null ? new ArrayList<>(variantInfoFilters) : new ArrayList<>();
	}

	public void setId(String id) {
		this.id = id;
	}

	public static class VariantInfoFilter {
		public VariantInfoFilter() {

		}

		public VariantInfoFilter(VariantInfoFilter filter) {
			this.numericVariantInfoFilters = new TreeMap<>(filter.numericVariantInfoFilters);
			this.categoryVariantInfoFilters = new TreeMap<>(filter.categoryVariantInfoFilters);
		}

		public Map<String, Filter.FloatFilter> numericVariantInfoFilters;
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
			return builder.toString();
		
		case CROSS_COUNT:
			writePartFormat("Cross Count Fields", crossCountFields, builder, true);
			break;
		case CATEGORICAL_CROSS_COUNT:
			writePartFormat("Categorical Cross Count Fields", categoryFilters.entrySet(), builder, true);
			break;
		case CONTINUOUS_CROSS_COUNT:
			writePartFormat("Continuous Cross Count Fields", numericFilters.entrySet(), builder, true);
			break;
		case OBSERVATION_COUNT:
			writePartFormat("Observation Count Fields", fields, builder, true);
			break;
		case DATAFRAME:
		case DATAFRAME_MERGED:
		case SECRET_ADMIN_DATAFRAME:
			writePartFormat("Data Export Fields", fields, builder, true);
			break;
		case DATAFRAME_TIMESERIES:
			writePartFormat("Data Export Fields", fields, builder, true);
			writePartFormat("Data Export Fields", requiredFields, builder, true);
			writePartFormat("Data Export Fields", anyRecordOf, builder, true);
			writePartFormat("Data Export Fields", numericFilters.keySet(), builder, true);
			writePartFormat("Data Export Fields", categoryFilters.keySet(), builder, true);
		case COUNT:
		case VARIANT_COUNT_FOR_QUERY:
		case AGGREGATE_VCF_EXCERPT:
		case VCF_EXCERPT:
			break;
		default:
			//no logic here; all enum values should be present above
			log.warn("Formatting not supported for type {}", expectedResultType);
		}

		writePartFormat("Required Fields", requiredFields, builder, false);
		writePartFormat("Numeric filters", numericFilters, builder);
		writePartFormat("Category filters", categoryFilters, builder);
		writePartFormat("Variant Info filters", variantInfoFilters, builder, false);
		writePartFormat("Any-Record-Of filters", getAllAnyRecordOf(), builder, true);

		return builder.toString();
	}
	
	/**
	 * For some elements of the query, we will iterate over the list of items and send them each to the string builder
	 * @param queryPart
	 * @param items
	 * @param builder
	 */
	@SuppressWarnings("rawtypes")
	private static void writePartFormat(String queryPart, Collection items, StringBuilder builder, boolean allowRollup) {
		final Collection collectionToWrite = Optional.ofNullable(items).orElseGet(Collections::emptyList);
		//same beginning
		builder.append(queryPart + ": [");  
		//if there are many elements, we want to truncate the display
		if(allowRollup && collectionToWrite.size() > 5) {
			builder.append("\n");
			showTopLevelValues(collectionToWrite, builder);
		}else {
			String sep1 = "";
			for(Object val : collectionToWrite) {
				builder.append(sep1 + val);
				sep1 = ", ";
			}
		}
		//same ending
		builder.append("]\n");
	}
	
	@SuppressWarnings("rawtypes")
	private static void showTopLevelValues(Collection varList, StringBuilder builder) {

		Map<String, Integer> countMap = new HashMap<String, Integer>();
		
		for(Object var : varList) {
			if(var instanceof String) {
				int index = ((String) var).startsWith("\\") ? 1 : 0;
				String firstLevel = ((String)var).split("\\\\")[index];
				
				Integer count = countMap.get(firstLevel);
				if(count == null) {
					count = 1;
				} else {
					count = count + 1;
				}
				countMap.put(firstLevel, count);
			} else {
				System.out.println("Object is not string! " + var);
			}
		}
		
		for(String key : countMap.keySet()) {
			builder.append("\t" + countMap.get(key) + " values under " + key + "\n");
		}
	}

	/**
	 * For other items that are mapped (e.g., 'variable -> range') we want to show both the name and the values requested (unless truncating)
	 * We can't combine this with the List/Collection method, as the two classes are not compatible (even though the method names are the same)
	 * @param queryPart
	 * @param varMap
	 * @param builder
	 */
	@SuppressWarnings("rawtypes")
	private static void writePartFormat(String queryPart, Map varMap, StringBuilder builder) {
		if(varMap == null || varMap.isEmpty()) {
			return;
		}

		//for the mapped elements, we never want to roll up the values; always show
		builder.append(queryPart + ": [");  
		String sep1 = "";
		for(Object key : varMap.keySet()) {
			builder.append(sep1 + key + ": ");
			Object value = varMap.get(key);
			
			if(value instanceof Object[]) {
				builder.append("{");
				String sep2 = "";
				for(Object val : (Object[])value) {
					builder.append(sep2 + val);
					sep2 = ", ";
				}
				builder.append("}");
			} else {
				builder.append(value);
			}
			sep1 = ", ";
		}
		builder.append("]\n");
	}
}
