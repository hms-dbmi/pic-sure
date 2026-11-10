package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileSharingV3ServiceTest {

    private QueryV3Service queryService;
    private FileSystemV3Service fileWriter;
    private FileSharingV3Service service;

    @BeforeEach
    void setup() {
        queryService = mock(QueryV3Service.class);
        fileWriter = mock(FileSystemV3Service.class);
        service = new FileSharingV3Service(queryService, fileWriter, mock(VariantListV3Processor.class), mock(LoggingClient.class));
    }

    @Test
    void phenotypicDataIsLookedUpByTheResultKeyNotTheRandomId() {
        UUID picsureId = UUID.randomUUID();
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME_TIMESERIES, picsureId, UUID.randomUUID());
        AsyncResult result = mock(AsyncResult.class);
        when(result.getStatus()).thenReturn(AsyncResult.Status.SUCCESS);
        when(queryService.getResultFor(picsureId)).thenReturn(result);
        when(fileWriter.writeResultToFile("phenotypic_data.csv", result, picsureId.toString())).thenReturn(true);

        assertTrue(service.createPhenotypicData(query));
    }

    @Test
    void phenotypicDataIsNotWrittenWhileTheJobIsRunning() {
        UUID picsureId = UUID.randomUUID();
        Query query = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME_TIMESERIES, picsureId, UUID.randomUUID());
        AsyncResult result = mock(AsyncResult.class);
        when(result.getStatus()).thenReturn(AsyncResult.Status.RUNNING);
        when(queryService.getResultFor(picsureId)).thenReturn(result);

        assertFalse(service.createPhenotypicData(query));
        verify(fileWriter, never()).writeResultToFile(any(), any(AsyncResult.class), any());
    }
}
