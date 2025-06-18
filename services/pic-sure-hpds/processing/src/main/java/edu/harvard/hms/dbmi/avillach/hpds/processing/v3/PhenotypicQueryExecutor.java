package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.SetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PhenotypicQueryExecutor {

    private static Logger log = LoggerFactory.getLogger(PhenotypicQueryExecutor.class);

    private final int CACHE_SIZE;

    private final String hpdsDataDirectory;

    private LoadingCache<String, PhenoCube<?>> store;

    private final PhenotypeMetaStore phenotypeMetaStore;

    public PhenotypicQueryExecutor(
            PhenotypeMetaStore phenotypeMetaStore,
            @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory
    ) {
        this.hpdsDataDirectory = hpdsDataDirectory;
        this.phenotypeMetaStore = phenotypeMetaStore;

        CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));

        store = initializeCache();

        if(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            List<String> cubes = new ArrayList<String>(phenotypeMetaStore.getColumnNames());
            int conceptsToCache = Math.min(cubes.size(), CACHE_SIZE);
            for(int x = 0;x<conceptsToCache;x++){
                try {
                    if(phenotypeMetaStore.getColumnMeta(cubes.get(x)).getObservationCount() == 0){
                        log.info("Rejecting : " + cubes.get(x) + " because it has no entries.");
                    }else {
                        store.get(cubes.get(x));
                        log.debug("loaded: " + cubes.get(x));
                        // +1 offset when logging to print _after_ each 10%
                        if((x + 1) % (conceptsToCache * .1)== 0) {
                            log.info("cached: " + (x + 1) + " out of " + conceptsToCache);
                        }
                    }
                } catch (ExecutionException e) {
                    log.error("an error occurred", e);
                }

            }
        }
    }

    public Set<Integer> getPatientSet(Query query) {
        List<PhenotypicClause> authorizationClauses = authorizationFiltersToPhenotypicClause(query.authorizationFilters());
        List<PhenotypicClause> mergedClauses = new ArrayList<>(authorizationClauses);
        mergedClauses.add(query.phenotypicClause());
        PhenotypicSubquery authorizedSubquery = new PhenotypicSubquery(
                null,
                mergedClauses,
                Operator.AND
        );
        Set<Integer> patientIdSet = evaluatePhenotypicClause(authorizedSubquery);
        return patientIdSet;
    }

    private List<PhenotypicClause> authorizationFiltersToPhenotypicClause(List<AuthorizationFilter> authorizationFilters) {
        return authorizationFilters.parallelStream().map(authorizationFilter -> {
            return new PhenotypicFilter(PhenotypicFilterType.FILTER, authorizationFilter.conceptPath(), authorizationFilter.values(), null, null, null);
        }).collect(Collectors.toList());
    }

    private Set<Integer> evaluatePhenotypicClause(PhenotypicClause phenotypicClause) {
        return switch (phenotypicClause) {
            case PhenotypicSubquery phenotypicSubquery -> evaluatePhenotypicSubquery(phenotypicSubquery);
            case PhenotypicFilter phenotypicFilter -> evaluatePhenotypicFilter(phenotypicFilter);
        };
    }

    private Set<Integer> evaluatePhenotypicFilter(PhenotypicFilter phenotypicFilter) {
        return switch (phenotypicFilter.phenotypicFilterType()) {
            case FILTER -> evaluateFilterFilter(phenotypicFilter);
            case ANY_RECORD_OF, REQUIRED -> evaluateAnyRecordOfFilter(phenotypicFilter);
        };
    }

    private Set<Integer> evaluateFilterFilter(PhenotypicFilter phenotypicFilter) {
        if (phenotypicFilter.values() != null) {
            Set<Integer> ids = new TreeSet<>();
            for (String category : phenotypicFilter.values()) {
                ids.addAll(getCube(phenotypicFilter.conceptPath()).getKeysForValue(category));
            }
            return ids;
        } else if (phenotypicFilter.max() != null || phenotypicFilter.min() != null) {
            return getCube(phenotypicFilter.conceptPath()).getKeysForRange(phenotypicFilter.min(), phenotypicFilter.max());
        } else {
            throw new IllegalArgumentException("Either values or one of min/max must be set for a filter");
        }
    }

    private Set<Integer> evaluateAnyRecordOfFilter(PhenotypicFilter phenotypicFilter) {
        Stream<Integer> stream = getCube(phenotypicFilter.conceptPath()).keyBasedIndex().stream();
        return stream.collect(Collectors.toSet());
    }

    private Set<Integer> evaluatePhenotypicSubquery(PhenotypicSubquery phenotypicSubquery) {
        return phenotypicSubquery.phenotypicClauses().parallelStream()
                .map(this::evaluatePhenotypicClause)
                // todo: replace union with our own implementation. Google's Sets functions return views
                .reduce(getReducer(phenotypicSubquery.operator()))
                .get();
    }

    private BinaryOperator<Set<Integer>> getReducer(Operator operator) {
        return switch (operator) {
            case OR -> SetUtils::union;
            case AND -> SetUtils::intersection;
        };
    }

    /**
     * If there are concepts in the list of paths which are already in the cache, push those to the
     * front of the list so that we don't evict and then reload them for concepts which are not yet
     * in the cache.
     *
     * @param paths
     * @param columnCount
     * @return
     */
    public ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
        int x;
        TreeSet<String> pathSet = new TreeSet<String>(paths);
        Set<String> residentKeys = Sets.intersection(pathSet, store.asMap().keySet());

        ArrayList<Integer> columnIndex = new ArrayList<Integer>();

        residentKeys.forEach(key ->{
            columnIndex.add(paths.indexOf(key) + 1);
        });

        Sets.difference(pathSet, residentKeys).forEach(key->{
            columnIndex.add(paths.indexOf(key) + 1);
        });

        for(x = 1;x < columnCount;x++) {
            columnIndex.add(x);
        }
        return columnIndex;
    }

    public PhenoCube getCube(String path) {
        try {
            return store.get(path);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a cube without throwing an error if not found.
     * Useful for federated pic-sure's where there are fewer
     * guarantees about concept paths.
     */
    public Optional<PhenoCube<?>> nullableGetCube(String path) {
        try {
            return Optional.ofNullable(store.get(path));
        } catch (CacheLoader.InvalidCacheLoadException | ExecutionException e) {
            return Optional.empty();
        }
    }

    /**
     * Load the variantStore object from disk and build the PhenoCube cache.
     *
     * @return
     */
    protected LoadingCache<String, PhenoCube<?>> initializeCache() {
        return CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                .build(
                        new CacheLoader<String, PhenoCube<?>>() {
                            public PhenoCube<?> load(String key) throws Exception {
                                try(RandomAccessFile allObservationsStore = new RandomAccessFile(hpdsDataDirectory + "allObservationsStore.javabin", "r");){
                                    ColumnMeta columnMeta = phenotypeMetaStore.getColumnMeta(key);
                                    if(columnMeta != null) {
                                        allObservationsStore.seek(columnMeta.getAllObservationsOffset());
                                        int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
                                        byte[] buffer = new byte[length];
                                        allObservationsStore.read(buffer);
                                        allObservationsStore.close();
                                        try (ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)))) {
                                            return (PhenoCube<?>)inStream.readObject();
                                        }
                                    }else {
                                        log.warn("ColumnMeta not found for : [{}]", key);
                                        return null;
                                    }
                                }
                            }
                        });
    }

    public TreeMap<String, ColumnMeta> getMetaStore() {
        return phenotypeMetaStore.getMetaStore();
    }

    public Set<Integer> getPatientIds() {
        return phenotypeMetaStore.getPatientIds();
    }
}
