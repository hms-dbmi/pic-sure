package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Class representing a store for phenotypic observations. The store manages caching of PhenoCube objects.
 */
@Component
public class PhenotypicObservationStore {

    private static final Logger log = LoggerFactory.getLogger(PhenotypicObservationStore.class);


    private final LoadingCache<String, PhenoCube<?>> phenoCubeCache;

    private final String hpdsDataDirectory;

    private final PhenotypeMetaStore phenotypeMetaStore;

    @Autowired
    public PhenotypicObservationStore(
        PhenotypeMetaStore phenotypeMetaStore, @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory,
        @Value("${CACHE_SIZE:100}") int cacheSize
    ) {
        this.phenotypeMetaStore = phenotypeMetaStore;
        this.hpdsDataDirectory = hpdsDataDirectory;
        phenoCubeCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build(new CacheLoader<>() {
            public PhenoCube<?> load(String key) {
                return loadPhenoCube(key);
            }
        });
    }

    // Constructor for testing only
    public PhenotypicObservationStore(
        PhenotypeMetaStore phenotypeMetaStore, LoadingCache<String, PhenoCube<?>> phenoCubeCache, int cacheSize
    ) {
        this.phenotypeMetaStore = phenotypeMetaStore;
        this.phenoCubeCache = phenoCubeCache;
        this.hpdsDataDirectory = "";
    }

    /**
     * For a given key (concept path), loads a PhenoCube from allObservationsStore.javabin in the configured hpdsDataDirectory. Throws an
     * exception if not found
     * @param key concept path to load a PhenoCube for
     * @return the PhenoCube for this key
     */
    public PhenoCube<?> loadPhenoCube(String key) {
        try (RandomAccessFile allObservationsStore = new RandomAccessFile(hpdsDataDirectory + "allObservationsStore.javabin", "r");) {
            ColumnMeta columnMeta = phenotypeMetaStore.getColumnMeta(key);
            if (columnMeta != null) {
                allObservationsStore.seek(columnMeta.getAllObservationsOffset());
                int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
                byte[] buffer = new byte[length];
                allObservationsStore.read(buffer);
                allObservationsStore.close();
                try (ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)))) {
                    return (PhenoCube<?>) inStream.readObject();
                }
            } else {
                log.warn("ColumnMeta not found for : [{}]", key);
                return null;
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets all keys (patient ids) with at least one value inside the specified range for a given concept path.
     */
    public Set<Integer> getKeysForRange(String conceptPath, Double min, Double max) {
        return getCube(conceptPath).map(cube -> ((PhenoCube<Double>) cube).getKeysForRange(min, max)).orElseGet(Set::of);
    }

    /**
     * Gets all keys (patient ids) with at least one matching value for a given concept path.
     */
    public Set<Integer> getKeysForValues(String conceptPath, Collection<String> values) {
        return getCube(conceptPath).map(cube -> {
            return values.stream().map(value -> ((PhenoCube<String>) cube).getKeysForValue(value)).reduce((set1, set2) -> {
                Set<Integer> union = new HashSet<>(set1);
                union.addAll(set2);
                return union;
            }).orElseGet(Set::of);
        }).orElseGet(Set::of);
    }


    /**
     * Gets all keys (patient ids) for a given concept path. This returns any patient with at least one value for this concept path
     */
    public List<Integer> getAllKeys(String conceptPath) {
        return getCube(conceptPath).map(PhenoCube::keyBasedIndex).orElseGet(List::of);
    }

    /**
     * Get a cube without throwing an error if not found. Useful for federated pic-sure's where there are fewer guarantees about concept
     * paths.
     */
    public Optional<PhenoCube<?>> getCube(String path) {
        try {
            return Optional.of(phenoCubeCache.get(path));
        } catch (CacheLoader.InvalidCacheLoadException | ExecutionException e) {
            return Optional.empty();
        }
    }

    /**
     * Gets currently cached keys. This can be useful for ordering a large number of lookups and avoiding cache churn
     */
    public Set<String> getCachedKeys() {
        return phenoCubeCache.asMap().keySet();
    }
}
