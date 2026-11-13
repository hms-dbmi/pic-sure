package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditLoggingFilter;
import reactor.core.publisher.Mono;

/**
 * Runs the audit filter in front of Spring MVC inside a real servlet container, so the response buffer, chunked transfer and the point
 * where the container commits a response are the ones production has. The handlers return the same shapes the HPDS controllers return.
 */
class AuditBytesContainerTest {

    private static final int STREAMED_BODY_BYTES = 3 * 1024 * 1024 + 17;
    private static final byte[] STREAMED_BODY = randomBytes(STREAMED_BODY_BYTES);

    @TempDir
    Path baseDir;

    private Tomcat tomcat;
    private LoggingClient loggingClient;
    private String baseUrl;

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(13720).nextBytes(bytes);
        return bytes;
    }

    @BeforeEach
    void startContainer() throws Exception {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);

        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", baseDir.toString());

        AnnotationConfigWebApplicationContext mvc = new AnnotationConfigWebApplicationContext();
        mvc.register(MvcConfig.class);
        mvc.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton("resultController", new ResultController()));
        Wrapper dispatcher = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(mvc));
        dispatcher.setAsyncSupported(true);
        dispatcher.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");

        FilterDef filterDef = new FilterDef();
        filterDef.setFilterName("audit");
        filterDef.setFilter(new AuditLoggingFilter(loggingClient, null, null));
        filterDef.setAsyncSupported("true");
        context.addFilterDef(filterDef);
        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName("audit");
        filterMap.addURLPattern("/*");
        context.addFilterMap(filterMap);

        tomcat.start();
        baseUrl = "http://localhost:" + tomcat.getConnector().getLocalPort();
    }

    @AfterEach
    void stopContainer() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private LoggingEvent sentEvent() {
        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, timeout(5000)).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void streamedResultLargerThanTheResponseBufferReportsEveryByte() throws Exception {
        HttpResponse<byte[]> response = get("/result");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Length").isEmpty(), "a streamed result carries no Content-Length");
        assertEquals(Long.valueOf(STREAMED_BODY_BYTES), sentEvent().getRequest().getBytes());
    }

    @Test
    void streamedResultReachesTheClientUnchanged() throws Exception {
        HttpResponse<byte[]> response = get("/result");

        assertArrayEquals(STREAMED_BODY, response.body());
        assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("chunked", response.headers().firstValue("Transfer-Encoding").orElseThrow());
    }

    @Test
    void jsonResponseReportsTheBytesTheClientReceived() throws Exception {
        HttpResponse<byte[]> response = get("/json");

        assertEquals("{\"status\":\"AVAILABLE\"}", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(Long.valueOf(response.body().length), sentEvent().getRequest().getBytes());
    }

    @Test
    void textResponseReportsTheBytesTheClientReceived() throws Exception {
        HttpResponse<byte[]> response = get("/text");

        assertEquals("Status : RUNNING", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("16", response.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(Long.valueOf(16), sentEvent().getRequest().getBytes());
    }

    @Test
    void responseWithNoBodyReportsZero() throws Exception {
        HttpResponse<byte[]> response = get("/empty");

        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
    }

    @Test
    void reactiveResponseIsStillPendingWhenTheEventIsSentAndReportsNoBytes() throws Exception {
        HttpResponse<byte[]> response = get("/reactive");

        assertEquals("{\"status\":\"AVAILABLE\"}", new String(response.body(), StandardCharsets.UTF_8));
        assertNull(sentEvent().getRequest().getBytes());
    }

    @EnableWebMvc
    static class MvcConfig {
    }

    /**
     * Handlers that return what the HPDS controllers return. An inner class, registered by hand, so that the component scan of the HPDS
     * application in this module's other tests does not find it.
     */
    @RestController
    class ResultController {

        @GetMapping("/result")
        ResponseEntity<InputStreamResource> result() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(new ByteArrayInputStream(STREAMED_BODY)));
        }

        @GetMapping("/json")
        Map<String, String> json() {
            return Map.of("status", "AVAILABLE");
        }

        @GetMapping("/text")
        ResponseEntity<String> text() {
            return ResponseEntity.status(400).body("Status : RUNNING");
        }

        @GetMapping("/empty")
        ResponseEntity<Void> empty() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/reactive")
        Mono<Map<String, String>> reactive() {
            return Mono.just(Map.of("status", "AVAILABLE"));
        }
    }
}
