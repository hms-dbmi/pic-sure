package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.QueryDecorator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PicSureServiceTest {

    @Autowired
    PicSureService subject;

    @MockBean
    QueryService queryService;
    @MockBean
    CountProcessor countProcessor;
    @MockBean
    VariantListProcessor variantListProcessor;
    @MockBean
    AbstractProcessor abstractProcessor;
    @MockBean
    Paginator paginator;
    @MockBean
    SignUrlService signUrlService;
    @MockBean
    FileSharingService fileSystemService;
    @MockBean
    QueryDecorator queryDecorator;
    @MockBean
    TestDataService testDataService;

    @Test
    void shouldUploadTestFile() {
        Query query = Mockito.mock(Query.class);
        query.setPicSureId(UUID.randomUUID().toString());
        Mockito.when(testDataService.uploadTestFile(query.getPicSureId())).thenReturn(true);

        ResponseEntity<String> actual = subject.writeQueryResult(query, "test_upload");
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldValidateUUID() {
        Query query = Mockito.mock(Query.class);
        Mockito.when(query.getExpectedResultType()).thenReturn(ResultType.COUNT);
        Mockito.when(query.getPicSureId()).thenReturn(":)");
        ResponseEntity<String> actual = subject.writeQueryResult(query, "patients");

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
        Assertions.assertEquals("The query pic-sure ID is not a UUID", actual.getBody());
    }

    @Test
    void should400ForBadResultType() {
        Query query = Mockito.mock(Query.class);
        Mockito.when(query.getExpectedResultType()).thenReturn(ResultType.COUNT);
        Mockito.when(query.getPicSureId()).thenReturn(UUID.randomUUID().toString());
        ResponseEntity<String> actual = subject.writeQueryResult(query, "patients");
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
        Assertions.assertEquals("The write endpoint only writes time series dataframes. Fix result type.", actual.getBody());

    }
}
