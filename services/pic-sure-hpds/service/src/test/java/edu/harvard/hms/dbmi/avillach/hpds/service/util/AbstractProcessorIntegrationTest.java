package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;

import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;


@Disabled
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = HpdsApplication.class)
@ActiveProfiles("integration-test")
public class AbstractProcessorIntegrationTest {

    @Autowired
    private AbstractProcessor abstractProcessor;

    @Test
    public void getPatientSubsetForQuery_validGeneWithVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"FRG1FP"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(5, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithMultipleVariantQuery() {
        Query query = new Query();
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
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(105, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"FRG1FP"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(4, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariant() {
        Query query = new Query();
        query.setRequiredFields(List.of("chr22,10942689,C,T"));
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(1, idList.size());


        query = new Query();
        query.setRequiredFields(List.of("chr20,1156012,G,A"));
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));

        idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(43, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_verifySeparateQueriesAreEquivalent() {
        Query query = new Query();
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));

        Set<Integer> numericIdList = abstractProcessor.getPatientSubsetForQuery(query);

        query = new Query();
        query.setRequiredFields(List.of("chr20,1156012,G,A"));

        Set<Integer> variantIdList = abstractProcessor.getPatientSubsetForQuery(query);


        query = new Query();
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));
        query.setRequiredFields(List.of("chr20,1156012,G,A"));

        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);

        assertEquals(Sets.intersection(numericIdList, variantIdList), bothIdList);
    }

    @Test
    public void getPatientSubsetForQuery_validMultipleRequiredVariant() {
        Query query = new Query();
        query.setRequiredFields(List.of("chr20,1156012,G,A"));
        query.setRequiredFields(List.of("chr22,10942689,C,T"));
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(1, idList.size());
    }


    @Test
    public void getVariantList_validGeneWithVariantQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"FRG1FP"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Integer variantCount = (Integer) new CountProcessor(abstractProcessor).runVariantCount(query).get("count");
        assertEquals(6, variantCount.intValue());
    }

    @Test
    public void getVariantList_validGeneWithMultipleVariantQuery() {
        Query query = new Query();
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
        query.setVariantInfoFilters(variantInfoFilters);

        Integer variantCount = (Integer) new CountProcessor(abstractProcessor).runVariantCount(query).get("count");
        assertEquals(49, variantCount.intValue());
    }

    @Test
    public void getVariantList_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"FRG1FP"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setNumericFilters(Map.of("\\laboratory\\allergen test\\", new Filter.DoubleFilter(0.0, 30.0)));


        Integer variantCount = (Integer) new CountProcessor(abstractProcessor).runVariantCount(query).get("count");
        assertEquals(5, variantCount.intValue());
    }

    @Test
    @Disabled("This functionality not working")
    public void getVariantList_validContinuousGenomicFilter() {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of();
        variantInfoFilter.numericVariantInfoFilters = Map.of("Variant_frequency_in_gnomAD", new Filter.FloatFilter(0.0f, 10.0f));
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(5, idList.size());
    }

    // todo: test variant filters that use the phenotipic query, and edge cases
}
