package edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange;

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
		this.picSureId = query.picSureId;
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

	private String picSureId;


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

	public String getPicSureId() {
		return picSureId;
	}

	public void setPicSureId(String picSureId) {
		this.picSureId = picSureId;
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
	}


}
