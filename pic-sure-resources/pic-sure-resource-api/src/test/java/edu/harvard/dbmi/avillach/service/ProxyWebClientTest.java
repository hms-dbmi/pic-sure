package edu.harvard.dbmi.avillach.service;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class ProxyWebClientTest {

    @Mock
    private HttpClient client;

    @Mock
    private HttpResponse response;

    @Mock
    private HttpEntity entity;

    @InjectMocks
    private ProxyWebClient subject;

    @Test
    public void shouldPostToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpPost.class)))
            .thenReturn(response);
        Mockito.when(response.getEntity())
            .thenReturn(entity);
        Mockito.when(entity.getContent())
            .thenReturn(new ByteArrayInputStream("{}".getBytes()));
        subject.client = client;

        Response actual = subject.postProxy("foo", "/my/cool/path", "{}");

        Assert.assertEquals(200, actual.getStatus());
    }

    @Test
    public void shouldGetToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpGet.class)))
            .thenReturn(response);
        Mockito.when(response.getEntity())
            .thenReturn(entity);
        Mockito.when(entity.getContent())
            .thenReturn(new ByteArrayInputStream("{}".getBytes()));
        subject.client = client;

        Response actual = subject.getProxy("bar", "/my/cool/path");

        Assert.assertEquals(200, actual.getStatus());
    }
}