package edu.harvard.hms.dbmi.avillach.hpds.test;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
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
public class QueryProcessorIntegrationTest {

    @Autowired
    private QueryProcessor abstractProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void getPatientSubsetForQuery_validEmptyQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(16, idList.size());
        assertTrue(idList.contains(200972));
        assertTrue(idList.contains(200971));
        assertTrue(idList.contains(200975));
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithMultipleVariantQuery() {
        Query query = new Query(
            List.of(),
            List.of(),
            null,
            List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996", "LOC101928576"), null, null)),
            ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(22, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null),
                List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996", "LOC101928576"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(4, idList.size());
    }



    @Test
    public void getPatientSubsetForQuery_validNumericPhenotypicQuery() {
        Query query = new Query(
            List.of(),
            List.of(),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null),
            null,
            ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(562, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validCategoricalPhenotypicQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", List.of("Finnish"), null, null, null),
                null,
                ResultType.COUNT);
        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(102, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validMultipleValueCategoricalPhenotypicQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", List.of("Finnish"), null, null, null),
                null,
                ResultType.COUNT);
        Set<Integer> finnishIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(102, finnishIdList.size());

        query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", List.of("Colombian"), null, null, null),
                null,
                ResultType.COUNT);
        Set<Integer> columbianIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(153, columbianIdList.size());

        query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", List.of("Finnish", "Colombian"), null, null, null),
                null,
                ResultType.COUNT);
        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(255, bothIdList.size());
        assertEquals(Sets.union(finnishIdList, columbianIdList), bothIdList);
    }

    @Test
    public void getPatientSubsetForQuery_validMultipleCategoricalPhenotypicQuery() {
        PhenotypicFilter finnishFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", List.of("Finnish"), null, null, null);
        Query query = new Query(
                List.of(),
                List.of(),
                finnishFilter,
                null,
                ResultType.COUNT);
        Set<Integer> finnishIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(102, finnishIdList.size());

        PhenotypicFilter femaleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", List.of("female"), null, null, null);
        query = new Query(
                List.of(),
                List.of(),
                femaleFilter,
                null,
                ResultType.COUNT);
        Set<Integer> femaleIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(2330, femaleIdList.size());

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(
                null,
                List.of(finnishFilter, femaleFilter),
                Operator.AND
        );
        query = new Query(
                List.of(),
                List.of(),
                phenotypicSubquery,
                null,
                ResultType.COUNT);
        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(64, bothIdList.size());
        assertEquals(Sets.intersection(finnishIdList, femaleIdList), bothIdList);
    }

    @Test
    public void getPatientSubsetForQuery_validMultiplePhenotypicQuery() {
        PhenotypicFilter ageFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null);
        Query query = new Query(
                List.of(),
                List.of(),
                ageFilter,
                null,
                ResultType.COUNT);
        Set<Integer> ageIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(562, ageIdList.size());

        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", List.of("male"), null, null, null);
        query = new Query(
                List.of(),
                List.of(),
                maleFilter,
                null,
                ResultType.COUNT);
        Set<Integer> sexIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(2648, sexIdList.size());

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(
                null,
                List.of(ageFilter, maleFilter),
                Operator.AND
        );
        query = new Query(
                List.of(),
                List.of(),
                phenotypicSubquery,
                null,
                ResultType.COUNT);
        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(269, bothIdList.size());
        assertEquals(Sets.intersection(ageIdList, sexIdList), bothIdList);
    }

    @Test
    public void getPatientSubsetForQuery_validMultipleNumericPhenotypicQuery() {
        PhenotypicFilter ageFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null);
        Query query = new Query(
                List.of(),
                List.of(),
                ageFilter,
                null,
                ResultType.COUNT);
        Set<Integer> ageIdList = abstractProcessor.getPatientSubsetForQuery(query);

        PhenotypicFilter heightFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\", null, 180.0f, null, null);
        query = new Query(
                List.of(),
                List.of(),
                heightFilter,
                null,
                ResultType.COUNT);
        Set<Integer> heightIdList = abstractProcessor.getPatientSubsetForQuery(query);

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(
                null,
                List.of(ageFilter, heightFilter),
                Operator.AND
        );
        query = new Query(
                List.of(),
                List.of(),
                phenotypicSubquery,
                null,
                ResultType.COUNT);
        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);

        assertEquals(Sets.intersection(ageIdList, heightIdList), bothIdList);
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariant() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G,LOC102723996,missense_variant", null, null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(7, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_invalidRequiredVariant() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5061,A,G", null, null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(0, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariantOldFormat() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G", null, null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(8, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_invalidVariantSpecQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5061,AAAA,GGGG", List.of("0/1", "1/1"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(0, idList.size());
    }


    @Test
    public void getPatientSubsetForQuery_validRequiredVariantOldFormatCategoryFilter() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G", List.of("0/1", "1/1"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(8, idList.size());
    }


    @Test
    public void getPatientSubsetForQuery_validRequiredVariantOldFormatCategoryFilterHomozygous() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G", List.of("1/1"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(3, idList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validRequiredVariantOldFormatCategoryFilterHeterozygous() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G", List.of("0/1"), null, null)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(5, idList.size());
    }
    @Test
    public void getPatientSubsetForQuery_verifySeparateQueriesAreEquivalent() {
        Query query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null),
                List.of(),
                ResultType.COUNT);

        Set<Integer> numericIdList = abstractProcessor.getPatientSubsetForQuery(query);

        query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("chr21,5032061,A,G", null, null, null)),
                ResultType.COUNT);

        Set<Integer> variantIdList = abstractProcessor.getPatientSubsetForQuery(query);


        query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null),
                List.of(new GenomicFilter("chr21,5032061,A,G", List.of("0/1"), null, null)),
                ResultType.COUNT);

        Set<Integer> bothIdList = abstractProcessor.getPatientSubsetForQuery(query);

        assertEquals(Sets.intersection(numericIdList, variantIdList), bothIdList);
    }


    @Test
    public void getVariantList_validGeneWithVariantQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null)),
                ResultType.COUNT);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(4, variantList.size());
    }

    @Test
    public void getVariantList_invalidGeneQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Gene_with_variant", List.of("NOTAGENE"), null, null)),
                ResultType.COUNT);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(0, variantList.size());
    }

    @Test
    public void getVariantList_validGeneWithMultipleVariantQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996", "LOC101928576"), null, null)),
                ResultType.COUNT);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(5, variantList.size());
    }

    @Test
    public void getVariantList_validGeneWithVariantQueryAndNumericQuery() {
        Query query = new Query(
                List.of(),
                List.of(),
                new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0f, 45.0f, null),
                List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996"), null, null)),
                ResultType.COUNT);


        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(2, variantList.size());
    }

    @Test
    public void getVariantList_validContinuousGenomicFilter() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Variant_frequency_in_gnomAD", null, 0.0001345F, 0.0001347f)),
                ResultType.COUNT);

        Collection<String> variantList = abstractProcessor.getVariantList(query);
        assertEquals(4, variantList.size());
    }

    @Test
    public void getPatientSubsetForQuery_validContinuousGenomicFilter() {
        Query query = new Query(
                List.of(),
                List.of(),
                null,
                List.of(new GenomicFilter("Variant_frequency_in_gnomAD", null, 0.0001345F, 0.0001347f)),
                ResultType.COUNT);

        Set<Integer> idList = abstractProcessor.getPatientSubsetForQuery(query);
        assertEquals(8, idList.size());
    }


}
