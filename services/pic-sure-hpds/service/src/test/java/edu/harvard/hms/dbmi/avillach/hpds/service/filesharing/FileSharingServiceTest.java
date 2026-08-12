package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PatientV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableAutoConfiguration
@SpringBootTest(classes = FileSharingV3Service.class)
public class FileSharingServiceTest {

    @MockBean
    QueryV3Service queryService;

    @MockBean
    FileSystemV3Service fileWriter;

    @MockBean
    VariantListV3Processor variantListProcessor;

    @MockBean
    PatientV3Processor patientProcessor;

    @MockBean
    ResultWriter resultWriter;

    @MockBean
    LoggingClient loggingClient;

    @Autowired
    FileSharingV3Service subject;

    @Test
    public void shouldCreatePhenotypicData() {
        UUID picsureId = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\"), List.of(), null, null, ResultType.DATAFRAME_TIMESERIES,
            picsureId, uuid
        );
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);

        Mockito.when(queryService.getResultFor(uuid)).thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("phenotypic_data.csv", result, picsureId.toString())).thenReturn(true);

        boolean actual = subject.createPhenotypicData(query);

        assertTrue(actual);
    }

    @Test
    public void shouldNotCreatePhenotypicData() {
        UUID picsureId = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\"), List.of(), null, null, ResultType.DATAFRAME_TIMESERIES,
            picsureId, uuid
        );
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.ERROR);

        Mockito.when(queryService.getResultFor(uuid)).thenReturn(result);

        boolean actual = subject.createPhenotypicData(query);

        assertFalse(actual);
    }

    @Test
    public void shouldCreateGenomicData() throws IOException {
        UUID picsureId = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\"), List.of(), null, null, ResultType.DATAFRAME_TIMESERIES,
            picsureId, uuid
        );
        String vcf = "lol lets put the whole vcf in a string";
        Mockito.when(variantListProcessor.runVcfExcerptQuery(query, true)).thenReturn(vcf);
        Mockito.when(fileWriter.writeResultToFile("genomic_data.tsv", vcf, picsureId.toString())).thenReturn(true);

        boolean actual = subject.createGenomicData(query);

        assertTrue(actual);
    }

    @Test
    void shouldCreatePatientsList() {
        UUID picsureId = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SUPERPOPULATION NAME\\"), List.of(), null, null, ResultType.PATIENTS, picsureId, uuid
        );

        AsyncResult result = new AsyncResult(query, patientProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);
        Mockito.when(queryService.getResultFor(uuid)).thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("patients.txt", result, picsureId.toString())).thenReturn(true);

        boolean actual = subject.createPatientList(query);

        Assertions.assertTrue(actual);
    }
}
