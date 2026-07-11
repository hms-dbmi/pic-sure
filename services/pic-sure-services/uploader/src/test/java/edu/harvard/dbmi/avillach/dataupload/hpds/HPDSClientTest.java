package edu.harvard.dbmi.avillach.dataupload.hpds;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HPDSClientTest {

    private static final String HPDS_URI = "http://hpds:8080/PIC-SURE/";

    private MockRestServiceServer server;
    private HPDSClient subject;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        subject = new HPDSClient(builder.build());
    }

    @Test
    void shouldInitializeQuery() {
        Query query = new Query();
        query.setPicSureId("my id");
        server.expect(requestTo(HPDS_URI + "query/sync")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());

        boolean actual = subject.initializeQuery(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotInitializeQuery() {
        Query query = new Query();
        query.setPicSureId("my id");
        server.expect(requestTo(HPDS_URI + "query/sync")).andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        boolean actual = subject.initializeQuery(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldWritePhenotypicData() {
        Query query = new Query();
        query.setPicSureId("my id");
        server.expect(requestTo(HPDS_URI + "write/phenotypic")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());

        boolean actual = subject.writePhenotypicData(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotWriteGenomicData() {
        Query query = new Query();
        query.setPicSureId("my id");
        server.expect(requestTo(HPDS_URI + "write/genomic")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        boolean actual = subject.writeGenomicData(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldWriteTestData() {
        Query query = new Query();
        query.setPicSureId("my id");
        server.expect(requestTo(HPDS_URI + "write/test_upload")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());

        boolean actual = subject.writeTestData(query);

        Assertions.assertTrue(actual);
    }
}
