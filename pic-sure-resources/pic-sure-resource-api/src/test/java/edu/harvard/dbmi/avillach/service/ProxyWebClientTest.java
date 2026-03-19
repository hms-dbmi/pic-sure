package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
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
import java.io.InputStream;
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
    private StatusLine statusLine;

    @Mock
    private ResourceRepository resourceRepository;

    @InjectMocks
    private ProxyWebClient subject;

    @Test
    public void shouldPostToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpPost.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(entity.getContentLength()).thenReturn(2L);
        Mockito.when(resourceRepository.getByColumn("name", "foo")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.postProxy("foo", "/my/cool/path", "{}", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
    }

    @Test
    public void shouldGetToProxy() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpGet.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(entity.getContentLength()).thenReturn(2L);
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
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(entity.getContentLength()).thenReturn(2L);
        Mockito.when(resourceRepository.getByColumn("name", "foo")).thenReturn(List.of(new Resource()));
        subject.client = client;

        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.put("site", List.of("bch"));
        Response actual = subject.postProxy("foo", "/my/cool/path", "{}", params, null);

        Assert.assertEquals(200, actual.getStatus());
    }

    @Test
    public void shouldBufferSmallResponse() throws IOException {
        Mockito.when(client.execute(Mockito.any(HttpGet.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        Mockito.when(entity.getContentLength()).thenReturn(2L);
        Mockito.when(resourceRepository.getByColumn("name", "bar")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.getProxy("bar", "/my/cool/path", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
        // Small responses should be buffered as a byte array
        assertTrue(actual.getEntity() instanceof byte[]);
    }

    @Test
    public void shouldStreamLargeResponse() throws IOException {
        ByteArrayInputStream largeBody = new ByteArrayInputStream("{}".getBytes());
        Mockito.when(client.execute(Mockito.any(HttpGet.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(largeBody);
        Mockito.when(entity.getContentLength()).thenReturn(20 * 1024 * 1024L);
        Mockito.when(resourceRepository.getByColumn("name", "bar")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.getProxy("bar", "/my/cool/path", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
        // Large responses should pass through the InputStream, not a byte array
        assertTrue(actual.getEntity() instanceof InputStream);
    }

    @Test
    public void shouldStreamWhenContentLengthUnknown() throws IOException {
        ByteArrayInputStream body = new ByteArrayInputStream("{}".getBytes());
        Mockito.when(client.execute(Mockito.any(HttpGet.class))).thenReturn(response);
        Mockito.when(response.getEntity()).thenReturn(entity);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(statusLine.getStatusCode()).thenReturn(200);
        Mockito.when(entity.getContent()).thenReturn(body);
        Mockito.when(entity.getContentLength()).thenReturn(-1L);
        Mockito.when(resourceRepository.getByColumn("name", "bar")).thenReturn(List.of(new Resource()));
        subject.client = client;

        Response actual = subject.getProxy("bar", "/my/cool/path", new MultivaluedHashMap<>(), null);

        Assert.assertEquals(200, actual.getStatus());
        // Unknown content length (-1) should stream to be safe
        assertTrue(actual.getEntity() instanceof InputStream);
    }
}
