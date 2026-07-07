package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class CountProcessorIntegrationTest {

    @Autowired
    private CountProcessor countProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void runCategoryCrossCounts_twoFilters() {
        Query query = new Query();
        query.setCrossCountFields(List.of());
        query.setCategoryFilters(Map.of(
                "\\open_access-1000Genomes\\data\\SEX\\", new String[] {"male", "female"},
                "\\open_access-1000Genomes\\data\\POPULATION NAME\\", new String[] {"Finnish"}
        ));
        query.setExpectedResultType(ResultType.CROSS_COUNT);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);
        assertEquals(2, crossCounts.size());
        assertEquals(38, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("male"));
        assertEquals(64, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("female"));
        assertEquals(102, crossCounts.get("\\open_access-1000Genomes\\data\\POPULATION NAME\\").get("Finnish"));
    }

    @Test
    public void runCategoryCrossCounts_snpFilter() {
        Query query = new Query();
        query.setCategoryFilters(Map.of(
                "chr21,5032061,A,G", new String[]{"0/1", "1/1"},
                "\\open_access-1000Genomes\\data\\SEX\\", new String[] {"male"}
        ));
        query.setExpectedResultType(ResultType.CROSS_COUNT);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);
        assertEquals(1, crossCounts.size());
        assertEquals(2, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("male"));
    }

}
