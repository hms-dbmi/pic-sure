package edu.harvard.hms.dbmi.avillach.hpds.service;

import de.siegmar.fastcsv.reader.CsvContainer;
import de.siegmar.fastcsv.reader.CsvReader;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
class QueryServiceTest {

    @Autowired
    private QueryService queryService;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void dataframeMulti() throws IOException, InterruptedException {
        Query query = new Query();
        List<Query.VariantInfoFilter> variantInfoFilters = new ArrayList<>();
        Query.VariantInfoFilter variantInfoFilter = new Query.VariantInfoFilter();
        variantInfoFilter.categoryVariantInfoFilters = Map.of("Gene_with_variant", new String[]{"LOC102723996", "LOC101928576"});
        variantInfoFilters.add(variantInfoFilter);
        query.setVariantInfoFilters(variantInfoFilters);
        query.setFields(List.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\"));
        query.setExpectedResultType(ResultType.DATAFRAME);

        AsyncResult asyncResult = queryService.runQuery(query);

        int retries = 0;
        while ((AsyncResult.Status.RUNNING.equals(asyncResult.getStatus()) || AsyncResult.Status.PENDING.equals(asyncResult.getStatus())) && retries < 10) {
            retries++;
            Thread.sleep(200);
        }

        assertEquals(AsyncResult.Status.SUCCESS, asyncResult.getStatus());
        CsvReader csvReader = new CsvReader();
        CsvContainer csvContainer = csvReader.read(asyncResult.getFile(), StandardCharsets.UTF_8);
        // 22 plus header
        assertEquals(23, csvContainer.getRows().size());
    }

}