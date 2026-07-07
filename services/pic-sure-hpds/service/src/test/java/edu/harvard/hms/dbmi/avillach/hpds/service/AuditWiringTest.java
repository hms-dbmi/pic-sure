package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.processing.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.patient.PatientProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeseriesProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@TestPropertySource(properties = {
    "SMALL_JOB_LIMIT=100",
    "SMALL_TASK_THREADS=2",
    "LARGE_TASK_THREADS=2",
})
class AuditWiringTest {

    // -- Bean under verification --------------------------------------------------
    @MockBean
    LoggingClient loggingClient;

    // -- Services wired by Spring (these are the subjects of the test) ------------
    @Autowired
    QueryService queryService;

    @Autowired
    QueryV3Service queryV3Service;

    @Autowired
    FileSharingService fileSharingService;

    @Autowired
    FileSharingV3Service fileSharingV3Service;

    // -- Mock all processor-level dependencies so the context can load -------------
    @MockBean
    GenomicProcessor genomicProcessor;
    @MockBean
    AbstractProcessor abstractProcessor;
    @MockBean
    QueryProcessor queryProcessor;
    @MockBean
    CountProcessor countProcessor;
    @MockBean
    TimeseriesProcessor timeseriesProcessor;
    @MockBean
    MultiValueQueryProcessor multiValueQueryProcessor;
    @MockBean
    PatientProcessor patientProcessor;
    @MockBean
    VariantListProcessor variantListProcessor;
    @MockBean
    QueryV3Processor queryV3Processor;
    @MockBean
    CountV3Processor countV3Processor;
    @MockBean
    TimeseriesV3Processor timeseriesV3Processor;
    @MockBean
    MultiValueQueryV3Processor multiValueQueryV3Processor;
    @MockBean
    PatientV3Processor patientV3Processor;
    @MockBean
    VariantListV3Processor variantListV3Processor;
    @MockBean
    QueryValidator queryValidator;
    @MockBean
    QueryExecutor queryExecutor;
    @MockBean
    PhenotypicQueryExecutor phenotypicQueryExecutor;
    @MockBean
    PhenotypicFilterValidator phenotypicFilterValidator;
    @MockBean
    PhenotypicObservationStore phenotypicObservationStore;

    // Shared processing dependencies
    @MockBean
    PhenotypeMetaStore phenotypeMetaStore;
    @MockBean
    ColumnSorter columnSorter;
    @MockBean
    DictionaryService dictionaryService;
    @MockBean
    SignUrlService signUrlService;

    @Test
    void contextLoads_servicesAreWired() {
        assertNotNull(queryService, "QueryService should be wired");
        assertNotNull(queryV3Service, "QueryV3Service should be wired");
        assertNotNull(fileSharingService, "FileSharingService should be wired");
        assertNotNull(fileSharingV3Service, "FileSharingV3Service should be wired");
    }

    @Test
    void loggingClientIsInjectedIntoQueryService() {
        Object client = ReflectionTestUtils.getField(queryService, "loggingClient");
        assertSame(loggingClient, client, "QueryService.loggingClient should be the mock");
    }

    @Test
    void loggingClientIsInjectedIntoQueryV3Service() {
        Object client = ReflectionTestUtils.getField(queryV3Service, "loggingClient");
        assertSame(loggingClient, client, "QueryV3Service.loggingClient should be the mock");
    }

    @Test
    void loggingClientIsInjectedIntoFileSharingService() {
        Object client = ReflectionTestUtils.getField(fileSharingService, "loggingClient");
        assertSame(loggingClient, client, "FileSharingService.loggingClient should be the mock");
    }

    @Test
    void loggingClientIsInjectedIntoFileSharingV3Service() {
        Object client = ReflectionTestUtils.getField(fileSharingV3Service, "loggingClient");
        assertSame(loggingClient, client, "FileSharingV3Service.loggingClient should be the mock");
    }
}
