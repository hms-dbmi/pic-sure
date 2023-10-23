package edu.harvard.hms.dbmi.avillach.hpds.processing;


import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AbstractProcessorTest {

    private AbstractProcessor abstractProcessor;

    @Mock
    private Map<String, FileBackedByteIndexedInfoStore> infoStores;

    @Mock
    private GenomicProcessor genomicProcessor;

    @Mock
    private LoadingCache<String, PhenoCube<?>> mockLoadingCache;

    public static final String GENE_WITH_VARIANT_KEY = "Gene_with_variant";
    public static final List<String> EXAMPLE_GENES_WITH_VARIANT = List.of("CDH8", "CDH9", "CDH10");

    @BeforeEach
    public void setup() {
        abstractProcessor = new AbstractProcessor(
                new PhenotypeMetaStore(
                        new TreeMap<>(),
                        new TreeSet<>()
                ),
                mockLoadingCache,
                infoStores,
                null,
                genomicProcessor,
                ""
        );
    }

    @Test
    public void getPatientSubsetForQuery_oneVariantCategoryFilter_indexFound() {
        Map<String, String[]> categoryVariantInfoFilters =
                Map.of(GENE_WITH_VARIANT_KEY, new String[] {EXAMPLE_GENES_WITH_VARIANT.get(0)});
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

        when(genomicProcessor.getPatientMask(isA(DistributableQuery.class))).thenReturn(Mono.just(new VariantMaskBitmaskImpl(new BigInteger("1100110011"))));
        when(genomicProcessor.patientMaskToPatientIdSet(eq(new VariantMaskBitmaskImpl(new BigInteger("1100110011"))))).thenReturn(Set.of(42, 99));

        List<Query.VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

        Query query = new Query();
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> patientSubsetForQuery = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(Set.of(42, 99), patientSubsetForQuery);
    }

    @Test
    public void getPatientSubsetForQuery_anyRecordOf_applyOrLogic() throws ExecutionException {
        PhenoCube mockPhenoCube = mock(PhenoCube.class);
        when(mockPhenoCube.keyBasedIndex()).thenReturn(List.of(42, 101));
        when(mockLoadingCache.get("good concept")).thenReturn(mockPhenoCube);
        when(mockLoadingCache.get("bad concept")).thenThrow(CacheLoader.InvalidCacheLoadException.class);

        Query query = new Query();
        query.setAnyRecordOf(List.of("good concept", "bad concept"));

        Set<Integer> patientSubsetForQuery = abstractProcessor.getPatientSubsetForQuery(query);
        assertFalse(patientSubsetForQuery.isEmpty());
    }



    @Test
    public void getPatientSubsetForQuery_anyRecordOfInvalidKey_returnEmpty() throws ExecutionException {
        when(mockLoadingCache.get("bad concept")).thenThrow(CacheLoader.InvalidCacheLoadException.class);

        Query query = new Query();
        query.setAnyRecordOf(List.of("bad concept"));

        Set<Integer> patientSubsetForQuery = abstractProcessor.getPatientSubsetForQuery(query);
        assertTrue(patientSubsetForQuery.isEmpty());
    }
}
