package edu.harvard.hms.dbmi.avillach.hpds.test;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class AbstractProcessorIntegrationTest {

    @Autowired
    private AbstractProcessor abstractProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(16, idList.size());
        assertTrue(idList.contains(200972));
        assertTrue(idList.contains(200971));
        assertTrue(idList.contains(200975));
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithMultipleVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996", "LOC101928576"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(22, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(4, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariant() {
        Query query = new Query();
        query.setRequiredFields(List.of("chr21,5032061,A,G,LOC102723996,missense_variant"));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(7, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariantOldFormat() {
        Query query = new Query();
        query.setRequiredFields(List.of("chr21,5032061,A,G"));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(8, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_verifySeparateQueriesAreEquivalent() {
        Query query = new Query();
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));

        Set<Integer> numericIdList = abstractProcessor.getPatientSubsetForQuery(query);

        query = new Query();
        query.setRequiredFields(List.of("chr21,5032061,A,G"));

        Set<Integer> variantIdList = abstractProcessor.getPatientSubsetForQuery(query);


        query = new Query();
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));
        query.setRequiredFields(List.of("chr21,5032061,A,G"));

        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);

        assertEquals(Sets.intersection(numericIdList, variantIdList), bothIdList);
    }


    @Test
    public void getVariantList_validGeneWithVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(4, variantList.size());
    }


    @Test
    public void getVariantList_validGeneQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(4, variantList.size());
    }


    @Test
    public void getVariantList_invalidGeneQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"NOTAGENE"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(0, variantList.size());
    }
    @Test
    public void getVariantList_validGeneWithMultipleVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996", "LOC101928576"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(5, variantList.size());
    }


    @Test
    public void getVariantList_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", new Filter.DoubleFilter(35.0, 45.0)));


        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(2, variantList.size());
    }

    @Test
    public void getVariantList_validContinuousGenomicFilter() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of();
        variantInfoFilter.numericVariantInfoFilters = Map.of("Variant_frequency_in_gnomAD", new Filter.FloatFilter(0.0001345F, 0.0001347f));
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(4, variantList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validContinuousGenomicFilter() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of();
        variantInfoFilter.numericVariantInfoFilters = Map.of("Variant_frequency_in_gnomAD", new Filter.FloatFilter(0.0001345F, 0.0001347f));
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(8, idList.size());
    }
}
