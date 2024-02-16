package edu.harvard.hms.dbmi.avillach.hpds.processing;


import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AbstractProcessorTest {

    private AbstractProcessor abstractProcessor;

    @Mock
    private Map<String, FileBackedByteIndexedInfoStore> infoStores;

    @Mock
    private GenomicProcessor genomicProcessor;

    public static final String GENE_WITH_VARIANT_KEY = "Gene_with_variant";
    public static final List<String> EXAMPLE_GENES_WITH_VARIANT = List.of("CDH8", "CDH9", "CDH10");


    @BeforeEach
    public void setup() {
        abstractProcessor = new AbstractProcessor(
                new PhenotypeMetaStore(
                        new TreeMap<>(),
                        new TreeSet<>()
                ),
                null,
                infoStores,
                null,
                genomicProcessor
        );
    }

    @Test
    public void getPatientSubsetForQuery_oneVariantCategoryFilter_indexFound() {
        //when(variantIndexCache.get(GENE_WITH_VARIANT_KEY, EXAMPLE_GENES_WITH_VARIANT.get(0))).thenReturn(new SparseVariantIndex(Set.of(2, 4, 6)));

        ArgumentCaptor<VariantIndex> argumentCaptor = ArgumentCaptor.forClass(VariantIndex.class);
        //when(patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(any(), argumentCaptor.capture())).thenReturn(List.of(Set.of(42)));

        Map<String, String[]> categoryVariantInfoFilters =
                Map.of(GENE_WITH_VARIANT_KEY, new String[] {EXAMPLE_GENES_WITH_VARIANT.get(0)});
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = categoryVariantInfoFilters;

        when(genomicProcessor.getPatientMask(isA(DistributableQuery.class))).thenReturn(Mono.just(new BigInteger("1100110011")));
        when(genomicProcessor.patientMaskToPatientIdSet(eq(new BigInteger("1100110011")))).thenReturn(Set.of(42, 99));

        List<Query.VariantInfoFilter> variantInfoFilters = List.of(variantInfoFilter);

        Query query = new Query();
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> patientSubsetForQuery = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(Set.of(42, 99), patientSubsetForQuery);
    }
}
