package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import java.util.*;

public class DistributableQuery {

    private List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();

    private Map<String, String[]> categoryFilters = new HashMap<>();

    private List<String> requiredFields = new ArrayList<>();

    private List<String> anyRecordOfFields = new ArrayList<>();

    private Set<Integer> patientIds;


    public void addRequiredVariantField(String path) {
        requiredFields.add(path);
    }
    public List<String> getRequiredFields() {
        return requiredFields;
    }

    public void addAnyRecordOfField(String path) {
        anyRecordOfFields.add(path);
    }

    public List<String> getAnyRecordOfFields() {
        return anyRecordOfFields;
    }

    public void addVariantSpecCategoryFilter(String key, String[] values) {
        categoryFilters.put(key, values);
    }
    public Map<String, String[]> getCategoryFilters() {
        return categoryFilters;
    }

    public void setVariantInfoFilters(Collection<Query.VariantInfoFilter> variantInfoFilters) {
        this.variantInfoFilters = variantInfoFilters != null ? new ArrayList<>(variantInfoFilters) : new ArrayList<>();
    }
    public List<Query.VariantInfoFilter> getVariantInfoFilters() {
        return new ArrayList<>(variantInfoFilters);
    }


    public DistributableQuery setPatientIds(Set<Integer> patientIds) {
        this.patientIds = patientIds;
        return this;
    }

    public Set<Integer> getPatientIds() {
        return patientIds;
    }

    public boolean hasFilters() {
        return !variantInfoFilters.isEmpty() || !categoryFilters.isEmpty() || !requiredFields.isEmpty();
    }
}
