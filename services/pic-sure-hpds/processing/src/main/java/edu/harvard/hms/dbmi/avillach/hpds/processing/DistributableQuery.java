package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import java.util.*;
import java.util.stream.Collectors;

public class DistributableQuery {

    private List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();

    private final Map<String, String[]> categoryFilters = new HashMap<>();

    private final Set<String> requiredFields = new HashSet<>();

    private Set<Integer> patientIds;


    public void addRequiredVariantField(String path) {
        requiredFields.add(path);
    }
    public Set<String> getRequiredFields() {
        return requiredFields;
    }

    public void addVariantSpecCategoryFilter(String key, String[] values) {
        categoryFilters.put(key, values);
    }
    public Map<String, String[]> getCategoryFilters() {
        return categoryFilters;
    }

    public void setVariantInfoFilters(Collection<Query.VariantInfoFilter> variantInfoFilters) {
        this.variantInfoFilters = variantInfoFilters.stream()
                .filter(variantInfoFilter -> !variantInfoFilter.categoryVariantInfoFilters.isEmpty() || !variantInfoFilter.numericVariantInfoFilters.isEmpty())
                .collect(Collectors.toList());
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
