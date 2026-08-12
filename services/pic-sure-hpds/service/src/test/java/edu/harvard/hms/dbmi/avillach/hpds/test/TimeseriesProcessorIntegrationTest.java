package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.TimeseriesV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class TimeseriesProcessorIntegrationTest {

    @Autowired
    private TimeseriesV3Processor timeseriesProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void runQueryForTimestamp() throws IOException, InterruptedException {
        PhenotypicClause phenotypicClause = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\", Set.of("American Ancestry"), null, null,
            null
        );
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\"), List.of(), phenotypicClause, null,
            ResultType.DATAFRAME_TIMESERIES, UUID.randomUUID(), UUID.randomUUID()
        );

        AsyncResult result =
            new AsyncResult(query, timeseriesProcessor, new CsvWriter(File.createTempFile("result-" + System.nanoTime(), ".sstmp")))
                .setStatus(AsyncResult.Status.PENDING).setId(UUID.randomUUID().toString());
        result.run();
        System.out.println(result.getStatus());
        Thread.sleep(1000);
        File file = result.getStream().getFile();
        String csv = Files.readString(file.toPath());

        csv.lines().skip(1).forEach(line -> {
            String timestampValue = line.split(",", -1)[4];
            assertNotEquals("", timestampValue);
        });
    }

    @Test
    public void runQueryForNullTimestamp() throws IOException, InterruptedException {
        PhenotypicClause phenotypicClause =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", Set.of("male"), null, null, null);
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SEX\\"), List.of(), phenotypicClause, null, ResultType.DATAFRAME_TIMESERIES,
            UUID.randomUUID(), UUID.randomUUID()
        );

        AsyncResult result =
            new AsyncResult(query, timeseriesProcessor, new CsvWriter(File.createTempFile("result-" + System.nanoTime(), ".sstmp")))
                .setStatus(AsyncResult.Status.PENDING).setId(UUID.randomUUID().toString());
        result.run();
        System.out.println(result.getStatus());
        Thread.sleep(1000);
        File file = result.getStream().getFile();
        String csv = Files.readString(file.toPath());

        csv.lines().skip(1).forEach(line -> {
            String timestampValue = line.split(",", -1)[4];
            assertEquals("", timestampValue);
        });
    }
}
