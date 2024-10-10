package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
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

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class VariantListProcessorIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(VariantListProcessorIntegrationTest.class);

    @Autowired
    private VariantListProcessor variantListProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithVariantQuery() throws IOException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

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
                if ("1/1".equals(column) || "0/1".equals(column))
                    patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset"));
            assertEquals("LOC102723996", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithVariantQueryNoCall() throws IOException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"ABC1"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

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
                if ("1/1".equals(column) || "0/1".equals(column))
                    patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset"));
            assertEquals("ABC1", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }


    @Test
    public void runVcfExcerptQuery_validGeneWithVariantAndPhenoQuery() throws IOException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));

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
                if ("1/1".equals(column) || "0/1".equals(column))
                    patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset"));
            assertEquals("LOC102723996", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }

    @Test
    public void runVcfExcerptQuery_validGeneWithNoCallVariantAndPhenoQuery() throws IOException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"ABC1"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));

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
                if ("1/1".equals(column) || "0/1".equals(column))
                    patientCount++;
            }
            assertTrue(patientCount > 0);
            assertEquals(patientCount + "/" + totalExpectedPatients, getValueAtColumn(columns, header, "Patients with this variant in subset"));
            assertEquals("ABC1", getValueAtColumn(columns, header, "Gene_with_variant"));
        });
    }

    @Test
    public void runVcfExcerptQuery_validQueryNoResults() throws IOException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(0.0, 1.0)));

        String vcfExerpt = variantListProcessor.runVcfExcerptQuery(query, true);
        assertEquals("No Variants Found\n", vcfExerpt);

    }

    private static String getValueAtColumn(String[] rowColumns, List<String> header, String key) {
        return rowColumns[header.indexOf(key)];
    }
}
