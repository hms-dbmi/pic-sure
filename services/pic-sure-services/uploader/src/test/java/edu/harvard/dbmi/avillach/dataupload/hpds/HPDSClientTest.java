package edu.harvard.dbmi.avillach.dataupload.hpds;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
class HPDSClientTest {

    @Mock
    HttpClient client;

    @Mock
    HttpClientContext context;

    @Mock
    HttpResponse response;

    @Mock
    StatusLine line;

    @InjectMocks
    HPDSClient subject;

    @Test
    void shouldInitializeQuery() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(200);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.initializeQuery(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotInitializeQuery() throws IOException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(404);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.initializeQuery(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldHandleException() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(404);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.initializeQuery(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldWritePhenotypicData() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(200);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.writePhenotypicData(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotWriteGenomicData() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(500);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.writeGenomicData(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldWriteTestData() throws IOException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.getStatusLine())
            .thenReturn(line);
        Mockito.when(line.getStatusCode())
            .thenReturn(200);
        Mockito.when(client.execute(Mockito.any(), Mockito.eq(context)))
            .thenReturn(response);

        boolean actual = subject.writeTestData(query);

        Assertions.assertTrue(actual);
    }
}