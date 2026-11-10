package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileSharingV3ServiceTest {

    private FileSystemV3Service fileWriter;
    private LoggingClient loggingClient;
    private FileSharingV3Service service;

    @BeforeEach
    void setup() {
        fileWriter = mock(FileSystemV3Service.class);
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        service = new FileSharingV3Service(fileWriter, mock(VariantListV3Processor.class), loggingClient);
    }

    @Test
    void phenotypicDataIsWrittenFromTheJobItIsGiven() {
        UUID picsureId = UUID.randomUUID();
        AsyncResult result = jobWithStatus(AsyncResult.Status.SUCCESS);
        when(fileWriter.writeResultToFile("phenotypic_data.csv", result, picsureId.toString())).thenReturn(true);

        assertTrue(service.createPhenotypicData(query(ResultType.DATAFRAME_TIMESERIES, picsureId), result));
    }

    @Test
    void patientListIsWrittenFromTheJobItIsGiven() {
        UUID picsureId = UUID.randomUUID();
        AsyncResult result = jobWithStatus(AsyncResult.Status.SUCCESS);
        when(fileWriter.writeResultToFile("patients.txt", result, picsureId.toString())).thenReturn(true);

        assertTrue(service.createPatientList(query(ResultType.PATIENTS, picsureId), result));
    }

    @Test
    void phenotypicDataIsNotWrittenWhileTheJobIsRunning() {
        AsyncResult result = jobWithStatus(AsyncResult.Status.RUNNING);

        assertFalse(service.createPhenotypicData(query(ResultType.DATAFRAME_TIMESERIES, UUID.randomUUID()), result));
        verify(fileWriter, never()).writeResultToFile(any(), any(AsyncResult.class), any());
    }

    @Test
    void fileWrittenEventCarriesTheResultKeyNotTheRandomId() {
        UUID picsureId = UUID.randomUUID();
        Query query = query(ResultType.DATAFRAME_TIMESERIES, picsureId);
        AsyncResult result = jobWithStatus(AsyncResult.Status.SUCCESS);
        when(result.getId()).thenReturn(picsureId.toString());
        when(fileWriter.writeResultToFile("phenotypic_data.csv", result, picsureId.toString())).thenReturn(true);

        service.createPhenotypicData(query, result);

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertEquals(picsureId.toString(), event.getValue().getMetadata().get("query_id"));
        assertNotEquals(query.id().toString(), event.getValue().getMetadata().get("query_id"));
    }

    private static AsyncResult jobWithStatus(AsyncResult.Status status) {
        AsyncResult job = mock(AsyncResult.class);
        when(job.getStatus()).thenReturn(status);
        return job;
    }

    private static Query query(ResultType resultType, UUID picsureId) {
        return new Query(List.of(), List.of(), null, List.of(), resultType, picsureId, UUID.randomUUID());
    }
}
