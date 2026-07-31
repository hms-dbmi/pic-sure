package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.UserRequestContext;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that Spring actually registers the AuditLoggingFilter and AuditInterceptor, and that @AuditEvent annotations on real controller
 * methods produce correct logging events through the full HTTP pipeline.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditMockMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LoggingClient loggingClient;

    // Mock all service dependencies so we don't need real data
    @MockitoBean
    QueryV3Service queryService;
    @MockitoBean
    CountV3Processor countProcessor;
    @MockitoBean
    VariantListV3Processor variantListProcessor;
    @MockitoBean
    Paginator paginator;
    @MockitoBean
    SignUrlService signUrlService;
    @MockitoBean
    FileSharingV3Service fileSystemService;
    @MockitoBean
    TestDataService testDataService;
    @MockitoBean
    PartitionedPhenotypicObservationStore partitionedPhenotypicObservationStore;

    @Test
    void postInfoEndpointProducesAuditEvent() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(true);

        mockMvc.perform(
            post("/PIC-SURE/v3/info").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"test\"}")
        ).andExpect(status().isOk());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, atLeastOnce()).send(captor.capture());

        LoggingEvent event = captor.getValue();
        assertEquals("OTHER", event.getEventType());
        assertEquals("info", event.getAction());
        assertEquals("POST", event.getRequest().getMethod());
        assertEquals(200, event.getRequest().getStatus());
        assertNotNull(event.getSessionId());
    }

    @Test
    void v3EndpointIncludesApiVersion() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(true);

        mockMvc.perform(
            post("/PIC-SURE/v3/info").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"test\"}")
        ).andExpect(status().isOk());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, atLeastOnce()).send(captor.capture());

        LoggingEvent event = captor.getValue();
        assertEquals("v3", event.getMetadata().get("api_version"));
    }

    @Test
    void bearerTokenIsPassedThrough() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(true);

        mockMvc.perform(
            post("/PIC-SURE/v3/info").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"test\"}")
                .header("Authorization", "Bearer mytoken")
                .header("X-Request-Id", "req-99")
        ).andExpect(status().isOk());

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        ArgumentCaptor<String> authCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reqIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(loggingClient, atLeastOnce()).send(eventCaptor.capture(), authCaptor.capture(), reqIdCaptor.capture());

        assertEquals("Bearer mytoken", authCaptor.getValue());
        assertEquals("req-99", reqIdCaptor.getValue());
    }

    @Test
    void notFoundReturnsOtherEventTypeWithNoAnnotation() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(true);

        mockMvc.perform(get("/PIC-SURE/v3/nonexistent")).andExpect(status().isNotFound());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, atLeastOnce()).send(captor.capture());

        LoggingEvent event = captor.getValue();
        // No @AuditEvent on a 404, so falls back to defaults
        assertEquals("OTHER", event.getEventType());
        assertNotNull(event.getError());
        assertEquals(404, event.getError().get("status"));
    }
}
