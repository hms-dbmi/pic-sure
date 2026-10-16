package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.ResultWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.patient.PatientProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class FileSharingServiceTest {

    @Mock
    QueryService queryService;

    @Mock
    FileSystemService fileWriter;

    @Mock
    VariantListProcessor variantListProcessor;

    @Mock
    PatientProcessor patientProcessor;

    @Mock
    ResultWriter resultWriter;

    @Mock
    LoggingClient loggingClient;

    FileSharingService subject;

    @BeforeEach
    public void setup() {
        subject = new FileSharingService(queryService, fileWriter, variantListProcessor, loggingClient);
    }

    @Test
    public void shouldCreatePhenotypicData() {
        Query query = new Query();
        query.setId("my-id");
        query.setPicSureId("my-ps-id");
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);

        Mockito.when(queryService.getResultFor("my-id")).thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("phenotypic_data.csv", result, "my-ps-id")).thenReturn(true);

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

        Mockito.when(queryService.getResultFor("my-id")).thenReturn(result);

        boolean actual = subject.createPhenotypicData(query);

        assertFalse(actual);
        Mockito.verify(fileWriter, Mockito.never()).writeResultToFile(Mockito.anyString(), Mockito.<AsyncResult>any(), Mockito.anyString());
        Mockito.verifyNoInteractions(loggingClient);
    }

    @Test
    public void shouldNotCreatePhenotypicDataWhenResultMissing() {
        Query query = new Query();
        query.setId("my-id");
        query.setPicSureId("my-ps-id");

        Mockito.when(queryService.getResultFor("my-id")).thenReturn(null);

        boolean actual = subject.createPhenotypicData(query);

        assertFalse(actual);
        Mockito.verify(fileWriter, Mockito.never()).writeResultToFile(Mockito.anyString(), Mockito.<AsyncResult>any(), Mockito.anyString());
        Mockito.verifyNoInteractions(loggingClient);
    }

    @Test
    public void shouldNotSendAuditEventWhenPhenotypicWriteFails() {
        Query query = new Query();
        query.setId("my-id");
        query.setPicSureId("my-ps-id");
        AsyncResult result = new AsyncResult(query, variantListProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);

        Mockito.when(queryService.getResultFor("my-id")).thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("phenotypic_data.csv", result, "my-ps-id")).thenReturn(false);

        boolean actual = subject.createPhenotypicData(query);

        assertFalse(actual);
        Mockito.verifyNoInteractions(loggingClient);
    }

    @Test
    public void shouldCreateGenomicData() throws IOException {
        Query query = new Query();
        query.setPicSureId("my-id");
        String vcf = "lol lets put the whole vcf in a string";
        Mockito.when(variantListProcessor.runVcfExcerptQuery(query, true)).thenReturn(vcf);
        Mockito.when(fileWriter.writeResultToFile("genomic_data.tsv", vcf, "my-id")).thenReturn(true);

        boolean actual = subject.createGenomicData(query);

        assertTrue(actual);
    }

    @Test
    public void shouldNotCreateGenomicData() throws IOException {
        Query query = new Query();
        query.setPicSureId("my-id");
        Mockito.when(variantListProcessor.runVcfExcerptQuery(query, true)).thenThrow(new IOException("oh no!"));

        boolean actual = subject.createGenomicData(query);

        assertFalse(actual);
        Mockito.verify(fileWriter, Mockito.never()).writeResultToFile(Mockito.anyString(), Mockito.<String>any(), Mockito.anyString());
        Mockito.verifyNoInteractions(loggingClient);
    }

    @Test
    void shouldCreatePatientsList() {
        Query query = new Query();
        query.setId("jasdijasd");
        query.setPicSureId("jasdijasd");
        query.setExpectedResultType(ResultType.PATIENTS);
        AsyncResult result = new AsyncResult(query, patientProcessor, resultWriter);
        result.setStatus(AsyncResult.Status.SUCCESS);
        Mockito.when(queryService.getResultFor("jasdijasd")).thenReturn(result);
        Mockito.when(fileWriter.writeResultToFile("patients.txt", result, "jasdijasd")).thenReturn(true);

        boolean actual = subject.createPatientList(query);

        Assertions.assertTrue(actual);
    }
}
