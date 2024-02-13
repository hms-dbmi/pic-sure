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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GenomicProcessorRestClientTest {

    private GenomicProcessorRestClient genomicProcessorRestClient = new GenomicProcessorRestClient("http://localhost:8090/");


    @Test
    public void simpleTest() {
        DistributableQuery distributableQuery = new DistributableQuery();

        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of(
                "Gene_with_variant", new String[]{"BRCA1"},
                "Variant_consequence_calculated", new String[]{"splice_donor_variant"}
        );
        variantInfoFilters.add(variantInfoFilter);
        distributableQuery.setVariantInfoFilters(variantInfoFilters);
        Set<Integer> patientIds = IntStream.range(53000, 53635).boxed().collect(Collectors.toSet());
        distributableQuery.setPatientIds(patientIds);

        BigInteger patientMaskForVariantInfoFilters = genomicProcessorRestClient.getPatientMask(distributableQuery).block();
        System.out.println(patientMaskForVariantInfoFilters);
    }

    @Test
    public void getInfoStoreColumns() {
        Set<String> infoStoreColumns = genomicProcessorRestClient.getInfoStoreColumns();
        assertTrue(infoStoreColumns.contains("Variant_consequence_calculated"));
    }
    @Test
    public void getInfoStoreValues() {
        Set<String> infoStoreValues = genomicProcessorRestClient.getInfoStoreValues("Variant_consequence_calculated");
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