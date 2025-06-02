package edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain;

import java.util.*;

public class Query {

    public Query() {

    }

    public Query(Query otherQuery) {
		if (otherQuery == null) {
			this.expectedResultType = ResultType.COUNT;
			this.crossCountFields = new ArrayList<>();
			this.fields = new ArrayList<>();
			this.requiredFields = new ArrayList<>();
			this.anyRecordOf = new ArrayList<>();
			this.anyRecordOfMulti = new ArrayList<>();
			this.numericFilters = new TreeMap<>();
			this.categoryFilters = new TreeMap<>();
			this.variantInfoFilters = new ArrayList<>();
			return;
		}
        
		this.id = otherQuery.id;

		this.crossCountFields = new ArrayList<>(otherQuery.crossCountFields);
		this.fields = new ArrayList<>(otherQuery.fields);
		this.requiredFields = new ArrayList<>(otherQuery.requiredFields);
		this.anyRecordOf = new ArrayList<>(otherQuery.anyRecordOf);

		this.anyRecordOfMulti = new ArrayList<>();
		for (List<String> list : otherQuery.anyRecordOfMulti) {
			this.anyRecordOfMulti.add(new ArrayList<>(list));
		}

		this.numericFilters = new TreeMap<String, Filter.DoubleFilter>(otherQuery.numericFilters);
        this.categoryFilters = new TreeMap<String, String[]>(otherQuery.categoryFilters);

		this.variantInfoFilters = new ArrayList<>();
		if (otherQuery.variantInfoFilters != null) {
			for (VariantInfoFilter filter : otherQuery.variantInfoFilters) {
				this.variantInfoFilters.add(new VariantInfoFilter(filter));
			}
		}
	}

    public ResultType expectedResultType = ResultType.COUNT;
    public List<String> crossCountFields = new ArrayList<String>();
    public List<String> fields = new ArrayList<String>();
    public List<String> requiredFields = new ArrayList<String>();
    public List<String> anyRecordOf = new ArrayList<String>();
    private List<List<String>> anyRecordOfMulti = new ArrayList<>();
    public Map<String, Filter.DoubleFilter> numericFilters = new TreeMap<String, Filter.DoubleFilter>();
    public Map<String, String[]> categoryFilters = new TreeMap<String, String[]>();
    public List<VariantInfoFilter> variantInfoFilters = new ArrayList<VariantInfoFilter>();
    public String id;

    public static class VariantInfoFilter {
        public VariantInfoFilter() {

        }

        public VariantInfoFilter(VariantInfoFilter filter) {
            this.numericVariantInfoFilters = new TreeMap<String, Filter.FloatFilter>(filter.numericVariantInfoFilters);
            this.categoryVariantInfoFilters = new TreeMap<String, String[]>(filter.categoryVariantInfoFilters);
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
            //Rethink these two as required fields work
            case CATEGORICAL_CROSS_COUNT:
            case CONTINUOUS_CROSS_COUNT:
                writePartFormat("Cross Count Fields", crossCountFields, builder, true);
                break;
            case OBSERVATION_COUNT:
                writePartFormat("Observation Count Fields", fields, builder, true);
                break;
            case DATAFRAME:
            case DATAFRAME_MERGED:
                writePartFormat("Data Export Fields", fields, builder, true);
                break;
            case COUNT:
            case VARIANT_COUNT_FOR_QUERY:
            case AGGREGATE_VCF_EXCERPT:
            case VCF_EXCERPT:
                break;
            default:
                //no logic here; all enum values should be present above
                System.out.println("Formatting not supported for type " + expectedResultType);
        }

        writePartFormat("Required Fields", requiredFields, builder, false);
        writePartFormat("Numeric filters", numericFilters, builder);
        writePartFormat("Category filters", categoryFilters, builder);
        writePartFormat("Variant Info filters", variantInfoFilters, builder, false);
        writePartFormat("Any-Record-Of filters", anyRecordOf, builder, true);
        writePartFormat("Any-Record-Of-Multi filters", anyRecordOfMulti, builder, true);

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
                    count = Integer.valueOf(1);
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
