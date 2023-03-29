package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class VariantIndexCache {

    private static Logger log = LoggerFactory.getLogger(VariantIndexCache.class);

    private final LoadingCache<String, VariantIndex> infoCache;

    private final String[] variantIndex;

    private final Map<String, FileBackedByteIndexedInfoStore> infoStores;

    private static final String COLUMN_AND_KEY_DELIMITER = "_____";
    /**
     * The maximum percentage of variants to use a sparse index vs a dense index. See {@link VariantIndex}
     */
    private static final double MAX_SPARSE_INDEX_RATIO = 0.1;

    public VariantIndexCache(String[] variantIndex, Map<String, FileBackedByteIndexedInfoStore> infoStores) {
        this.variantIndex = variantIndex;
        this.infoStores = infoStores;
        this.infoCache = CacheBuilder.newBuilder()
                .weigher(weigher).maximumWeight(10000000000000L).build(cacheLoader);
    }

    public VariantIndex get(String key) {
        return infoCache.getUnchecked(key);
    }
    public VariantIndex get(String column, String key) {
        return infoCache.getUnchecked(columnAndKey(column, key));
    }
    private String columnAndKey(String column, String key) {
        return column + COLUMN_AND_KEY_DELIMITER + key;
    }

    private final Weigher<String, VariantIndex> weigher = new Weigher<String, VariantIndex>(){
        @Override
        public int weigh(String key, VariantIndex value) {
            if (value instanceof DenseVariantIndex) {
                return ((DenseVariantIndex) value).getVariantIndexMask().length;
            } else if (value instanceof SparseVariantIndex) {
                return ((SparseVariantIndex) value).getVariantIds().size();
            } else {
                throw new IllegalArgumentException("Unknown VariantIndex implementation: " + value.getClass());
            }
        }
    };
    private final CacheLoader<String, VariantIndex> cacheLoader = new CacheLoader<>() {
        @Override
        public VariantIndex load(String infoColumn_valueKey) throws IOException {
            log.debug("Calculating value for cache for key " + infoColumn_valueKey);
            long time = System.currentTimeMillis();
            String[] column_and_value = infoColumn_valueKey.split(COLUMN_AND_KEY_DELIMITER);
            String[] variantArray = infoStores.get(column_and_value[0]).getAllValues().get(column_and_value[1]);

            if ((double)variantArray.length / (double)variantIndex.length < MAX_SPARSE_INDEX_RATIO ) {
                Set<Integer> variantIds = new HashSet<>();
                for(String variantSpec : variantArray) {
                    int variantIndexArrayIndex = Arrays.binarySearch(variantIndex, variantSpec);
                    variantIds.add(variantIndexArrayIndex);
                }
                return new SparseVariantIndex(variantIds);
            } else {
                boolean[] variantIndexArray = new boolean[variantIndex.length];
                int x = 0;
                for(String variantSpec : variantArray) {
                    int variantIndexArrayIndex = Arrays.binarySearch(variantIndex, variantSpec);
                    // todo: shouldn't this be greater than or equal to 0? 0 is a valid index
                    if (variantIndexArrayIndex > 0) {
                        variantIndexArray[variantIndexArrayIndex] = true;
                    }
                }
                log.debug("Cache value for key " + infoColumn_valueKey + " calculated in " + (System.currentTimeMillis() - time) + " ms");
                return new DenseVariantIndex(variantIndexArray);
            }
        }
    };

}
