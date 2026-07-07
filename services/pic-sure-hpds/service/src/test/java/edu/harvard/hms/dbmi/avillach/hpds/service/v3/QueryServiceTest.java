package edu.harvard.hms.dbmi.avillach.hpds.service.v3;

import de.siegmar.fastcsv.reader.CsvContainer;
import de.siegmar.fastcsv.reader.CsvReader;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = HpdsApplication.class)
@ActiveProfiles("integration-test")
class QueryServiceTest {

    @Autowired
    private QueryV3Service queryService;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void dataframeMulti() throws IOException, InterruptedException {
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\"), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0, 45.0, null),
            List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996", "LOC101928576"), null, null)), ResultType.DATAFRAME,
            null, null
        );

        AsyncResult asyncResult = queryService.runQuery(query);

        int retries = 0;
        while (
            (AsyncResult.Status.RUNNING.equals(asyncResult.getStatus()) || AsyncResult.Status.PENDING.equals(asyncResult.getStatus()))
                && retries < 10
        ) {
            retries++;
            Thread.sleep(200);
        }

        assertEquals(AsyncResult.Status.SUCCESS, asyncResult.getStatus());
        CsvReader csvReader = new CsvReader();
        CsvContainer csvContainer = csvReader.read(asyncResult.getFile(), StandardCharsets.UTF_8);
        // 22 plus header
        assertEquals(8, csvContainer.getRows().size());
    }

    @Test
    public void runQuery_dataframeSelectInvalidConcept_doNotFail() throws IOException, InterruptedException {
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\"), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, 35.0, 45.0, null),
            List.of(new GenomicFilter("Gene_with_variant", List.of("LOC102723996", "LOC101928576"), null, null)), ResultType.DATAFRAME,
            null, null
        );

        AsyncResult asyncResult = queryService.runQuery(query);

        int retries = 0;
        while (
            (AsyncResult.Status.RUNNING.equals(asyncResult.getStatus()) || AsyncResult.Status.PENDING.equals(asyncResult.getStatus()))
                && retries < 10
        ) {
            retries++;
            Thread.sleep(200);
        }

        assertEquals(AsyncResult.Status.SUCCESS, asyncResult.getStatus());
        CsvReader csvReader = new CsvReader();
        CsvContainer csvContainer = csvReader.read(asyncResult.getFile(), StandardCharsets.UTF_8);
        // 22 plus header
        assertEquals(8, csvContainer.getRows().size());
    }

}
