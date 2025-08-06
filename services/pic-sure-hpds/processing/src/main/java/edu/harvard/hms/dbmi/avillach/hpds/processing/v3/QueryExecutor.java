package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.cache.CacheLoader;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.UncheckedExecutionException;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Component
public class QueryExecutor {

    private static Logger log = LoggerFactory.getLogger(QueryExecutor.class);


    private final GenomicProcessor genomicProcessor;

    private final PhenotypicQueryExecutor phenotypicQueryExecutor;

    private final PhenotypeMetaStore phenotypeMetaStore;


    @Autowired
    public QueryExecutor(
        GenomicProcessor genomicProcessor, PhenotypicQueryExecutor phenotypicQueryExecutor, PhenotypeMetaStore phenotypeMetaStore
    ) {
        this.genomicProcessor = genomicProcessor;
        this.phenotypicQueryExecutor = phenotypicQueryExecutor;
        this.phenotypeMetaStore = phenotypeMetaStore;
    }

    public Set<String> getInfoStoreColumns() {
        return genomicProcessor.getInfoStoreColumns();
    }



    /**
     * Executes a query and returns the ids of all matching patients
     *
     * @param query
     * @return
     */
    public Set<Integer> getPatientSubsetForQuery(Query query) {
        Set<Integer> patientIdSet = phenotypicQueryExecutor.getPatientSet(query);
        DistributableQuery distributableQuery = queryToDistributableQuery(query, patientIdSet);
        // NULL (representing no phenotypic filters, i.e. all patients) or not empty patient ID sets require a genomic query.
        // Otherwise, short circuit and return no patients
        if (
            (distributableQuery.getPatientIds() == null || !distributableQuery.getPatientIds().isEmpty()) && distributableQuery.hasFilters()
        ) {
            Mono<VariantMask> patientMaskForVariantInfoFilters = genomicProcessor.getPatientMask(distributableQuery);
            return patientMaskForVariantInfoFilters.map(genomicProcessor::patientMaskToPatientIdSet).block();
        }

        if (distributableQuery.getPatientIds() == null) {
            return phenotypicQueryExecutor.getPatientIds();
        }
        return distributableQuery.getPatientIds();
    }

    private DistributableQuery queryToDistributableQuery(Query query, Set<Integer> patientIds) {
        DistributableQuery distributableQuery = new DistributableQuery().setPatientIds(patientIds);

        if (query.genomicFilters().isEmpty()) {
            return distributableQuery;
        }

        edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter variantInfoFilters =
            new edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter();
        variantInfoFilters.numericVariantInfoFilters = new HashMap<>();
        variantInfoFilters.categoryVariantInfoFilters = new HashMap<>();

        query.genomicFilters().forEach(genomicFilter -> {
            if (VariantUtils.pathIsVariantSpec(genomicFilter.key())) {
                if (genomicFilter.values() == null || genomicFilter.values().isEmpty()) {
                    distributableQuery.addVariantSpecCategoryFilter(genomicFilter.key(), new String[] {"0/1", "1/1"});
                } else {
                    distributableQuery.addVariantSpecCategoryFilter(genomicFilter.key(), genomicFilter.values().toArray(new String[0]));
                }

            } else {
                if (genomicFilter.values() != null) {
                    variantInfoFilters.categoryVariantInfoFilters.put(genomicFilter.key(), genomicFilter.values().toArray(new String[0]));
                } else if (genomicFilter.max() != null || genomicFilter.min() != null) {
                    variantInfoFilters.numericVariantInfoFilters
                        .put(genomicFilter.key(), new Filter.FloatFilter(genomicFilter.min(), genomicFilter.max()));
                }
            }
        });

        distributableQuery.setVariantInfoFilters(List.of(variantInfoFilters));
        return distributableQuery;
    }

    public Collection<String> getVariantList(Query query) {
        Set<Integer> patientIdSet = phenotypicQueryExecutor.getPatientSet(query);
        DistributableQuery distributableQuery = queryToDistributableQuery(query, patientIdSet);
        return genomicProcessor.getVariantList(distributableQuery).block();
    }

    public List<InfoColumnMeta> getInfoStoreMeta() {
        return genomicProcessor.getInfoColumnMeta();
    }

    public List<String> searchInfoConceptValues(String conceptPath, String query) {
        try {
            return genomicProcessor.getInfoStoreValues(conceptPath).stream()
                .filter(variableValue -> variableValue.toUpperCase(Locale.ENGLISH).contains(query.toUpperCase(Locale.ENGLISH)))
                .collect(Collectors.toList());
        } catch (UncheckedExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }


    public ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
        return phenotypicQueryExecutor.useResidentCubesFirst(paths, columnCount);
    }

    public Map<String, ColumnMeta> getDictionary() {
        return phenotypicQueryExecutor.getMetaStore();
    }

    public List<String> getPatientIds() {
        return genomicProcessor.getPatientIds();
    }

    public Optional<VariableVariantMasks> getMasks(String path, VariantBucketHolder<VariableVariantMasks> variantMasksVariantBucketHolder) {
        return genomicProcessor.getMasks(path, variantMasksVariantBucketHolder);
    }

    // todo: handle this locally, we do not want this in the genomic processor
    protected VariantMask createMaskForPatientSet(Set<Integer> patientSubset) {
        return genomicProcessor.createMaskForPatientSet(patientSubset);
    }

    public SequencedSet<String> getAllConceptPaths(Query query) {
        SequencedSet<String> allConceptPaths = new LinkedHashSet<>(query.select());
        List<String> allFilterPaths =
            query.allFilters().stream().flatMap(phenotypicFilter -> (switch (phenotypicFilter.phenotypicFilterType()) {
                case FILTER, REQUIRED -> List.of(phenotypicFilter.conceptPath());
                case ANY_RECORD_OF -> phenotypeMetaStore.getChildConceptPaths(phenotypicFilter.conceptPath());
            }).stream()).toList();
        allConceptPaths.addAll(allFilterPaths);
        return allConceptPaths;
    }

}
