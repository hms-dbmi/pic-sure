package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;

/**
 * Pins HPDS's side of the v3 wire contract -- verb, request body, and response shape -- against {@code pic-sure-hpds-query-service}'s
 * {@code ResourceWebClientTest}, which pins the same hops from the client side. The two files are the two halves of one contract; a change
 * here that is not mirrored there breaks the hop.
 *
 * <p>What is asserted: submissions bind the BARE v3 {@link Query} (no {@code QueryRequest} envelope), reads carry no body at all (status is
 * a GET; result/signed-url are bodyless POSTs), responses are the shared {@code contracts.query.v3} records, and a body carrying a field
 * the contract does not model is a 400 rather than a silently dropped value.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PicSureV3ServiceWebTest {

    private static final String BARE_QUERY = """
        {"select":["\\\\age\\\\"],"expectedResultType":"COUNT"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoggingClient loggingClient;

    // v3 collaborators -- the subjects of these tests
    @MockitoBean
    private QueryV3Service queryV3Service;
    @MockitoBean
    private QueryExecutor queryExecutor;

    // v1 collaborators, mocked only so the context loads without real HPDS data on disk
    @MockitoBean
    private QueryService queryService;
    @MockitoBean
    private CountProcessor countProcessor;
    @MockitoBean
    private VariantListProcessor variantListProcessor;
    @MockitoBean
    private AbstractProcessor abstractProcessor;
    @MockitoBean
    private SignUrlService signUrlService;
    @MockitoBean
    private FileSharingService fileSharingService;
    @MockitoBean
    private TestDataService testDataService;

    @BeforeEach
    void stubDictionary() {
        when(queryExecutor.getDictionary()).thenReturn(new TreeMap<>());
        when(queryExecutor.getInfoStoreMeta()).thenReturn(List.of());
    }

    private static AsyncResult runningResult(String id) {
        AsyncResult result = mock(AsyncResult.class);
        when(result.getId()).thenReturn(id);
        when(result.getStatus()).thenReturn(AsyncResult.Status.RUNNING);
        when(result.getQueuedTime()).thenReturn(1000L);
        when(result.getCompletedTime()).thenReturn(0L);
        Query query = mock(Query.class);
        when(query.picsureId()).thenReturn(UUID.randomUUID());
        when(query.id()).thenReturn(UUID.randomUUID());
        when(result.getQuery()).thenReturn(query);
        return result;
    }

    // --- submissions: the bare v3 Query, nothing wrapping it ---

    @Test
    void queryBindsTheBareV3QueryWithNoEnvelope() throws Exception {
        AsyncResult running = runningResult("rr-1"); // built outside when(...) -- nested stubbing is an UnfinishedStubbing error
        when(queryV3Service.runQuery(any())).thenReturn(running);

        try (MockedStatic<Crypto> crypto = mockStatic(Crypto.class)) {
            crypto.when(() -> Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)).thenReturn(true);
            mockMvc.perform(post("/PIC-SURE/v3/query").contentType(MediaType.APPLICATION_JSON).content(BARE_QUERY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resourceResultId").value("rr-1"))
                .andExpect(jsonPath("$.status").value("PENDING")) // RUNNING maps to the PIC-SURE-wide PENDING
                .andExpect(jsonPath("$.resourceStatus").value("RUNNING"))
                // the legacy QueryStatus echo is gone: the response is the shared contract record
                .andExpect(jsonPath("$.picsureResultId").doesNotExist());
        }

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(queryV3Service).runQuery(captor.capture());
        assertThat(captor.getValue().select()).containsExactly("\\age\\");
        assertThat(captor.getValue().expectedResultType()).isEqualTo(ResultType.COUNT);
    }

    /**
     * Strict binding is what makes the bare contract enforceable: a caller still sending the old envelope's {@code resourceUUID} gets an
     * immediate 400 naming the field, not a 200 with the value quietly discarded.
     */
    @Test
    void envelopeFieldsOnTheBareQueryBodyAreRejectedWith400() throws Exception {
        mockMvc.perform(
            post("/PIC-SURE/v3/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"select\":[],\"resourceUUID\":\"" + UUID.randomUUID() + "\"}")
        ).andExpect(status().isBadRequest());

        verify(queryV3Service, never()).runQuery(any());
    }

    @Test
    void querySyncBindsTheBareV3Query() throws Exception {
        try (MockedStatic<Crypto> crypto = mockStatic(Crypto.class)) {
            crypto.when(() -> Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)).thenReturn(true);
            mockMvc.perform(post("/PIC-SURE/v3/query/sync").contentType(MediaType.APPLICATION_JSON).content(BARE_QUERY))
                .andExpect(status().isOk());
        }
    }

    // --- reads: no body anywhere ---

    @Test
    void statusIsAGetWithNoBodyAndEmitsTheContractShape() throws Exception {
        UUID id = UUID.randomUUID();
        AsyncResult running = runningResult("rr-9");
        when(queryV3Service.getStatusFor(id.toString())).thenReturn(running);

        mockMvc.perform(get("/PIC-SURE/v3/query/{id}/status", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceResultId").value("rr-9")).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.resourceStatus").value("RUNNING")).andExpect(jsonPath("$.sizeInBytes").value(0))
            .andExpect(jsonPath("$.picsureResultId").doesNotExist());
    }

    /** The POST form of /status is gone (breaking, intended): only GET is mapped. */
    @Test
    void postToStatusIsNoLongerMapped() throws Exception {
        mockMvc.perform(post("/PIC-SURE/v3/query/{id}/status", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void resultTakesNoRequestBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(queryV3Service.getResultFor(id)).thenReturn(null);

        // no content type, no body -- a 404 for the missing result proves the handler was reached and bound
        mockMvc.perform(post("/PIC-SURE/v3/query/{id}/result", id)).andExpect(status().isNotFound());
    }

    @Test
    void signedUrlTakesNoRequestBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(queryV3Service.getResultFor(id)).thenReturn(null);

        mockMvc.perform(post("/PIC-SURE/v3/query/{id}/signed-url", id)).andExpect(status().isNotFound());
    }

    // --- search ---

    @Test
    void searchBindsTheTypedSearchRequest() throws Exception {
        mockMvc.perform(post("/PIC-SURE/v3/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"blood pressure\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.searchQuery").value("blood pressure"));
    }

    @Test
    void searchRejectsUnknownFieldsWith400() throws Exception {
        mockMvc.perform(
            post("/PIC-SURE/v3/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\",\"resourceUUID\":\"nope\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void searchValuesReturnsTheTypedPage() throws Exception {
        when(queryExecutor.searchInfoConceptValues(anyString(), anyString())).thenReturn(List.of("BRCA1", "BRCA2"));

        mockMvc
            .perform(
                get("/PIC-SURE/v3/search/values/").param("genomicConceptPath", "\\gene\\").param("query", "BRCA").param("page", "1")
                    .param("size", "10")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.results[0]").value("BRCA1")).andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.total").value(2));
    }

    // --- write: the two guards the deleted v1 PicSureServiceTest covered, re-homed onto the v3 endpoint ---

    /** {@code test_upload} short-circuits every other guard: the upload service's verdict is the response. */
    @Test
    void writeUploadsTheTestFileForTheTestUploadDataType() throws Exception {
        UUID picsureId = UUID.randomUUID();
        when(testDataService.uploadTestFile(picsureId.toString())).thenReturn(true);

        mockMvc.perform(
            post("/PIC-SURE/v3/write/{dataType}", "test_upload").contentType(MediaType.APPLICATION_JSON)
                .content("{\"select\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":\"" + picsureId + "\"}")
        ).andExpect(status().isOk());

        verify(testDataService).uploadTestFile(picsureId.toString());
    }

    /** Only DATAFRAME_TIMESERIES and PATIENTS are writable; anything else is a 400 naming the reason, never a silent no-op. */
    @Test
    void writeRejectsANonTimeseriesResultTypeWith400() throws Exception {
        mockMvc
            .perform(
                post("/PIC-SURE/v3/write/{dataType}", "patients").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"select\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":\"" + UUID.randomUUID() + "\"}")
            ).andExpect(status().isBadRequest())
            .andExpect(content().string("The write endpoint only writes time series dataframes. Fix result type."));

        verify(queryV3Service, never()).runQuery(any());
        verify(fileSharingService, never()).createPatientList(any());
    }

    // --- info + the deleted endpoint ---

    @Test
    void infoTakesNoRequestBody() throws Exception {
        mockMvc.perform(post("/PIC-SURE/v3/info")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("PhenoCube v1.0-SNAPSHOT"))
            .andExpect(jsonPath("$.queryFormats[0].name").value("PhenoCube Query Format"));
    }

    /** {@code /query/format} was a debug echo of the parsed query; it is deleted, not deprecated. */
    @Test
    void queryFormatIsGone() throws Exception {
        mockMvc.perform(post("/PIC-SURE/v3/query/format").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }
}
