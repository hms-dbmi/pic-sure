package edu.harvard.dbmi.avillach.dataupload.hpds;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

@SpringBootTest
class HPDSClientTest {

    @Mock
    HttpClient client;

    @Mock
    HttpResponse<Object> response;

    @InjectMocks
    HPDSClient subject;

    @Test
    void shouldInitializeQuery() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.statusCode())
            .thenReturn(200);
        Mockito.when(client.send(Mockito.any(), Mockito.any()))
            .thenReturn(response);

        boolean actual = subject.initializeQuery(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotInitializeQuery() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.statusCode())
            .thenReturn(404);
        Mockito.when(client.send(Mockito.any(), Mockito.any()))
            .thenReturn(response);

        boolean actual = subject.initializeQuery(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldHandleException() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.statusCode())
            .thenReturn(404);
        Mockito.when(client.send(Mockito.any(), Mockito.any()))
            .thenThrow(new IOException());

        boolean actual = subject.initializeQuery(query);

        Assertions.assertFalse(actual);
    }

    @Test
    void shouldWritePhenotypicData() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.statusCode())
            .thenReturn(200);
        Mockito.when(client.send(Mockito.any(), Mockito.any()))
            .thenReturn(response);

        boolean actual = subject.writePhenotypicData(query);

        Assertions.assertTrue(actual);
    }

    @Test
    void shouldNotWriteGenomicData() throws IOException, InterruptedException {
        Query query = new Query();
        query.setPicSureId("my id");

        Mockito.when(response.statusCode())
            .thenReturn(500);
        Mockito.when(client.send(Mockito.any(), Mockito.any()))
            .thenReturn(response);

        boolean actual = subject.writeGenomicData(query);

        Assertions.assertFalse(actual);
    }
}