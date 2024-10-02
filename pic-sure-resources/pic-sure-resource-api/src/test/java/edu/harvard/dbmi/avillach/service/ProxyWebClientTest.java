package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
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

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class ProxyWebClientTest {

    @Mock
    private HttpClient client;

    @Mock
    private HttpResponse response;

    @Mock
    private HttpEntity entity;

    @Mock
    private ResourceRepository resourceRepository;

    @InjectMocks
    private ProxyWebClient subject;

    @Test
    public void shouldPostToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpPost.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(resourceRepository.getByColumn("name", "foo")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.postProxy("foo", "/my/cool/path", "{}", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
    }

    @Test
    public void shouldGetToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpGet.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(resourceRepository.getByColumn("name", "bar")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.getProxy("bar", "/my/cool/path", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
    }

    @Test
    public void shouldRejectNastyHost() {
        Mockito.when(resourceRepository.getByColumn("name", "an.evil.domain")).thenReturn(List.of());

        Response actual = subject.postProxy("an.evil.domain", "hax", null, new MultivaluedHashMap<>(), null);
        assertEquals(400, actual.getStatus());

        actual = subject.getProxy("an.evil.domain", "hax", new MultivaluedHashMap<>(), null);
        assertEquals(400, actual.getStatus());
    }

    @Test
    public void shouldPostWithParams() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpPost.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(resourceRepository.getByColumn("name", "foo")).thenReturn(List.of(new Resource()));
        subject.client = client;

        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.put("site", List.of("bch"));
        Response actual = subject.postProxy("foo", "/my/cool/path", "{}", params, null);

        Assert.assertEquals(200, actual.getStatus());
    }
}
