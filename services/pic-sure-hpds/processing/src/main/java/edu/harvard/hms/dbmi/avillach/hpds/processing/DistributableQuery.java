package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DistributableQuery {

    private Query genomicQuery;
    private List<Set<Integer>> phenotypicQueryPatientSets;

    public DistributableQuery() {
        genomicQuery = new Query();
        phenotypicQueryPatientSets = new ArrayList<>();
    }

    public Query getGenomicQuery() {
        return genomicQuery;
    }

    public List<Set<Integer>> getPhenotypicQueryPatientSets() {
        return phenotypicQueryPatientSets;
    }

    public void addAndClausePatients(Set<Integer> patientSet) {
        synchronized (patientSet) {
            phenotypicQueryPatientSets.add(patientSet);
        }
    }

    public void addRequiredVariantField(String path) {
        synchronized (genomicQuery) {
            genomicQuery.getRequiredFields().add(path);
        }
    }

    public void addVariantSpecCategoryFilter(String key, String[] categoryFilters) {
        synchronized (genomicQuery) {
            genomicQuery.getCategoryFilters().put(key, categoryFilters);
        }
    }
}
