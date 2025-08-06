package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class VariantListV3ProcessorIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(VariantListV3ProcessorIntegrationTest.class);

    @Autowired
    private VariantListV3Processor variantListProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithVariantQuery() {
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null);
        Query query = new Query(List.of(), List.of(), null, List.of(genomicFilter), ResultType.COUNT, null, null);

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        log.debug(vcfExerpt);
        String[] vcfExcerptLines = vcfExerpt.split("\\n");

        int totalExpectedPatients = 16;
        int totalExpectedVariants = 4;

        // there should be a line per variant, plus one line for the header
        assertEquals(totalExpectedVariants + 1, vcfExcerptLines.length);
        List<String> header = Arrays.asList(vcfExcerptLines[0].split("\\t"));
        String[] variantLines = Arrays.copyOfRange(vcfExcerptLines, 1, vcfExcerptLines.length);
        Arrays.stream(variantLines).forEach(line -> {
            String[] columns = line.split("\\t");
            assertEquals("chr21", columns[0]);
            int patientCount = 0;
            for (String column : columns) {
                if ("1/1".equals(column) || "0/1".equals(column)) patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(
                patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset")
            );
            assertEquals("LOC102723996", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithVariantQueryNoCall() {
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("ABC1"), null, null);
        Query query = new Query(List.of(), List.of(), null, List.of(genomicFilter), ResultType.COUNT, null, null);

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        log.debug(vcfExerpt);
        String[] vcfExcerptLines = vcfExerpt.split("\\n");

        int totalExpectedPatients = 16;
        int totalExpectedVariants = 4;

        // there should be a line per variant, plus one line for the header
        assertEquals(totalExpectedVariants + 1, vcfExcerptLines.length);
        List<String> header = Arrays.asList(vcfExcerptLines[0].split("\\t"));
        String[] variantLines = Arrays.copyOfRange(vcfExcerptLines, 1, vcfExcerptLines.length);
        Arrays.stream(variantLines).forEach(line -> {
            String[] columns = line.split("\\t");
            assertEquals("chr20", columns[0]);
            int patientCount = 0;
            for (String column : columns) {
                if ("1/1".equals(column) || "0/1".equals(column)) patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(
                patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset")
            );
            assertEquals("ABC1", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }


    @Test
    public void runVcfExcerptQuery_validGeneWithVariantAndPhenoQuery() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0, 45.0, null);
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null);
        Query query = new Query(List.of(), List.of(), phenotypicFilter, List.of(genomicFilter), ResultType.COUNT, null, null);

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        log.debug(vcfExerpt);
        String[] vcfExcerptLines = vcfExerpt.split("\\n");

        int totalExpectedPatients = 4;
        int totalExpectedVariants = 2;

        // there should be a line per variant, plus one line for the header
        assertEquals(totalExpectedVariants + 1, vcfExcerptLines.length);
        List<String> header = Arrays.asList(vcfExcerptLines[0].split("\\t"));
        String[] variantLines = Arrays.copyOfRange(vcfExcerptLines, 1, vcfExcerptLines.length);
        Arrays.stream(variantLines).forEach(line -> {
            String[] columns = line.split("\\t");
            assertEquals("chr21", columns[0]);
            int patientCount = 0;
            for (String column : columns) {
                if ("1/1".equals(column) || "0/1".equals(column)) patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(
                patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset")
            );
            assertEquals("LOC102723996", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithNoCallVariantAndPhenoQuery() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0, 45.0, null);
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("ABC1"), null, null);
        Query query = new Query(List.of(), List.of(), phenotypicFilter, List.of(genomicFilter), ResultType.COUNT, null, null);

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        log.debug(vcfExerpt);
        String[] vcfExcerptLines = vcfExerpt.split("\\n");

        int totalExpectedPatients = 4;
        int totalExpectedVariants = 2;

        // there should be a line per variant, plus one line for the header
        assertEquals(totalExpectedVariants + 1, vcfExcerptLines.length);
        List<String> header = Arrays.asList(vcfExcerptLines[0].split("\\t"));
        String[] variantLines = Arrays.copyOfRange(vcfExcerptLines, 1, vcfExcerptLines.length);
        Arrays.stream(variantLines).forEach(line -> {
            String[] columns = line.split("\\t");
            assertEquals("chr20", columns[0]);
            int patientCount = 0;
            for (String column : columns) {
                if ("1/1".equals(column) || "0/1".equals(column)) patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(
                patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset")
            );
            assertEquals("ABC1", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }


    @Test
    public void runVcfExcerptQuery_validQueryNoResults() {
        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 0.0, 1.0, null);
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null);
        Query query = new Query(List.of(), List.of(), phenotypicFilter, List.of(genomicFilter), ResultType.COUNT, null, null);

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        assertEquals("No Variants Found\n", vcfExerpt);
    }

    @Test
    public void runVariantListQuery_validQuery_returnVariants() {
        GenomicFilter genomicFilter = new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null);
        Query query = new Query(List.of(), List.of(), null, List.of(genomicFilter), ResultType.COUNT, null, null);

        String variantList = variantListProcessor.runVariantListQuery(query);
        assertEquals(
            "[chr21,5032061,A,G,LOC102723996,missense_variant, chr21,5033988,C,G,LOC102723996,synonymous_variant, chr21,5034028,C,T,LOC102723996,missense_variant, chr21,5033914,A,G,LOC102723996,missense_variant]",
            variantList
        );
    }

    private static String getValueAtColumn(String[] rowColumns, List<String> header, String key) {
        return rowColumns[header.indexOf(key)];
    }
}
