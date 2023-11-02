package edu.harvard.hms.dbmi.avillach.hpds.processing.genomic;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import org.junit.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GenomicProcessorRestClientTest {

    private GenomicProcessorRestClient genomicProcessorRestClient = new GenomicProcessorRestClient("http://localhost:8090/");


    @Test
    public void test() throws JsonProcessingException {
        DistributableQuery distributableQuery = new DistributableQuery();

        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of(
                "Gene_with_variant", new String[]{"FRG1FP"}
        );
        Query.VariantInfoFilter variantInfoFilter2 = new Query.VariantInfoFilter();
        variantInfoFilter2.categoryVariantInfoFilters = Map.of(
                "Gene_with_variant", new String[]{"ACTG1P3"}
        );
        variantInfoFilters.add(variantInfoFilter2);
        distributableQuery.setVariantInfoFilters(variantInfoFilters);
        distributableQuery.setPatientIds(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        BigInteger patientMaskForVariantInfoFilters = genomicProcessorRestClient.getPatientMask(distributableQuery);
        System.out.println(patientMaskForVariantInfoFilters);
    }
}