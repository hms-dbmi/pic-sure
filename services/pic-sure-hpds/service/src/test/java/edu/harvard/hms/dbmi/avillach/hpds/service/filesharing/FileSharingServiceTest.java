package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.patient.PatientProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableAutoConfiguration
@SpringBootTest(classes = FileSharingService.class)
public class FileSharingServiceTest {

    @MockBean
    QueryService queryService;

    @MockBean
    FileSystemService fileWriter;

    @MockBean
    VariantListProcessor variantListProcessor;

    @MockBean
    PatientProcessor patientProcessor;

    @MockBean
    ResultWriter resultWriter;

    @Autowired
    FileSharingService subject;

    @Test
    public void shouldCreatePhenotypicData() {
        Query query = new Query();
        query.setId("my-id");
        query.setPicSureId("my-ps-id");
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);

        Mockito.when(queryService.getResultFor("my-id"))
            .thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("phenotypic_data.csv", result, "my-ps-id"))
            .thenReturn(true);

        boolean actual = subject.createPhenotypicData(query);

        assertTrue(actual);
    }

    @Test
    public void shouldNotCreatePhenotypicData() {
        Query query = new Query();
        query.setId("my-id");
        query.setPicSureId("my-ps-id");
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.ERROR);

        Mockito.when(queryService.getResultFor("my-id"))
            .thenReturn(result);

        boolean actual = subject.createPhenotypicData(query);

        assertFalse(actual);
    }

    @Test
    public void shouldCreateGenomicData() throws IOException {
        Query query = new Query();
        query.setPicSureId("my-id");
        String vcf = "lol lets put the whole vcf in a string";
        Mockito.when(variantListProcessor.runVcfExcerptQuery(query, true))
            .thenReturn(vcf);
        Mockito.when(fileWriter.writeResultToFile("genomic_data.tsv", vcf, "my-id"))
            .thenReturn(true);

        boolean actual = subject.createGenomicData(query);

        assertTrue(actual);
    }

    @Test
    public void shouldNotCreateGenomicData() throws IOException {
        Query query = new Query();
        query.setPicSureId("my-id");
        Mockito.when(variantListProcessor.runVcfExcerptQuery(query, true))
            .thenThrow(new IOException("oh no!"));

        boolean actual = subject.createGenomicData(query);

        assertFalse(actual);
    }

    @Test
    void shouldCreatePatientsList() {
        Query query = new Query();
        query.setId("jasdijasd");
        query.setPicSureId("jasdijasd");
        query.setExpectedResultType(ResultType.PATIENTS);
        AsyncResult result = new AsyncResult(query, patientProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);
        Mockito.when(queryService.getResultFor("jasdijasd"))
            .thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("patients.txt", result, "jasdijasd"))
            .thenReturn(true);

        boolean actual = subject.createPatientList(query);

        Assertions.assertTrue(actual);
    }
}