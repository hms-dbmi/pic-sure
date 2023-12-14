package edu.harvard.hms.dbmi.avillach.hpds.processing.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GenomicProcessorRestClientTest {

    private GenomicProcessorRestClient genomicProcessorRestClient = new GenomicProcessorRestClient("http://localhost:8090/");


    @Test
    public void simpleTest() {
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

        BigInteger patientMaskForVariantInfoFilters = genomicProcessorRestClient.getPatientMask(distributableQuery).block();
        System.out.println(patientMaskForVariantInfoFilters);
    }

    @Test
    public void getInfoStoreColumns() {
        List<String> infoStoreColumns = genomicProcessorRestClient.getInfoStoreColumns();
        assertTrue(infoStoreColumns.contains("Variant_consequence_calculated"));
    }
    @Test
    public void getInfoStoreValues() {
        List<String> infoStoreValues = genomicProcessorRestClient.getInfoStoreValues("Variant_consequence_calculated");
        assertTrue(infoStoreValues.contains("inframe_deletion"));
    }

    @Test
    public void getInfoColumnMeta() {
        List<InfoColumnMeta> infoColumnMeta = genomicProcessorRestClient.getInfoColumnMeta();
        for (InfoColumnMeta columnMeta : infoColumnMeta) {
            if (columnMeta.getKey().equals("Variant_consequence_calculated")) {
                return;
            }
        }
        throw new RuntimeException("Variant_consequence_calculated not found in info column meta");
    }
}