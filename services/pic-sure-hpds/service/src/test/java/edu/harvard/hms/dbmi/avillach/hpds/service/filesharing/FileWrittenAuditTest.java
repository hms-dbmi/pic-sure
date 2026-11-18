package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileWrittenAuditTest {

    private LoggingClient loggingClient;

    @BeforeEach
    void setup() {
        loggingClient = mock(LoggingClient.class);
    }

    @Test
    void sendsADataAccessEventNamingTheQueryAndTheKindOfFile() {
        when(loggingClient.isEnabled()).thenReturn(true);

        FileWrittenAudit.send(loggingClient, "query-1", "phenotypic");

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(event.capture());
        assertEquals("DATA_ACCESS", event.getValue().getEventType());
        assertEquals("data.file.written", event.getValue().getAction());
        assertEquals("query-1", event.getValue().getMetadata().get("query_id"));
        assertEquals("phenotypic", event.getValue().getMetadata().get("data_type"));
    }

    @Test
    void sendsNothingThroughADisabledClient() {
        when(loggingClient.isEnabled()).thenReturn(false);

        FileWrittenAudit.send(loggingClient, "query-1", "patients");

        verify(loggingClient, never()).send(any());
    }

    @Test
    void doesNothingWithoutAClient() {
        assertDoesNotThrow(() -> FileWrittenAudit.send(null, "query-1", "genomic"));
    }

    @Test
    void aFailedSendDoesNotReachTheCaller() {
        when(loggingClient.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("logging service is down")).when(loggingClient).send(any());

        assertDoesNotThrow(() -> FileWrittenAudit.send(loggingClient, "query-1", "genomic"));
    }
}
