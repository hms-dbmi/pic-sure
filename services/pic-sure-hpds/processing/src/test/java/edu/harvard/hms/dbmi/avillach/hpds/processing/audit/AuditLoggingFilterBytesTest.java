package edu.harvard.hms.dbmi.avillach.hpds.processing.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Covers the {@code bytes} field of the audit event: what the filter reports for a body written to the output stream, and when it reports 0
 * or leaves the field unset.
 */
class AuditLoggingFilterBytesTest {

    private AuditLoggingFilter filter;
    private LoggingClient loggingClient;

    @BeforeEach
    void setup() {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        filter = new AuditLoggingFilter(loggingClient, null, null);
    }

    private MockHttpServletRequest mockRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.168.1.1");
        request.setLocalAddr("10.0.0.1");
        request.setLocalPort(8080);
        return request;
    }

    private LoggingEvent sentEvent() {
        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void testBytesCountsWhatTheOutputStreamReceived() throws Exception {
        byte[] body = "{\"count\":42}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ServletOutputStream out = res.getOutputStream();
            out.write(body, 0, 4);
            out.write(body[4]);
            out.write(body, 5, body.length - 5);
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertEquals(Long.valueOf(body.length), sentEvent().getRequest().getBytes());
        assertArrayEquals(body, response.getContentAsByteArray());
    }

    @Test
    void testBytesIgnoresAContentLengthHeaderThatDisagreesWithTheBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ((HttpServletResponse) res).setHeader("Content-Length", "999");
            res.getOutputStream().write(new byte[10]);
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertEquals(Long.valueOf(10), sentEvent().getRequest().getBytes());
        assertEquals("999", response.getHeader("Content-Length"));
    }

    @Test
    void testBytesIsZeroWhenNoBodyIsWritten() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(mockRequest("/PIC-SURE/query", "POST"), response, new MockFilterChain());

        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
    }

    @Test
    void testBytesIsUnsetWhenTheBodyIsWrittenThroughTheWriter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("Status : RUNNING");
        };

        filter.doFilter(mockRequest("/PIC-SURE/info", "GET"), response, chain);

        assertNull(sentEvent().getRequest().getBytes());
        assertEquals("Status : RUNNING", response.getContentAsString());
    }

    @Test
    void testBytesIsUnsetWhenTheWriterIsObtainedAndNeverUsed() throws Exception {
        FilterChain chain = (req, res) -> res.getWriter();

        filter.doFilter(mockRequest("/PIC-SURE/info", "GET"), new MockHttpServletResponse(), chain);

        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testBytesIsZeroForANoContentStatusWhateverWasWritten() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ((HttpServletResponse) res).setStatus(204);
            res.getOutputStream().write(new byte[31]);
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
    }

    @Test
    void testBytesIsZeroForANotModifiedStatusWhateverWasWritten() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ((HttpServletResponse) res).setStatus(304);
            res.getOutputStream().write(new byte[31]);
        };

        filter.doFilter(mockRequest("/PIC-SURE/info", "GET"), response, chain);

        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
    }

    @Test
    void testBytesStartsOverWhenTheBufferIsReset() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.getOutputStream().write(new byte[100]);
            res.resetBuffer();
            res.getOutputStream().write(new byte[7]);
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertEquals(Long.valueOf(7), sentEvent().getRequest().getBytes());
        assertEquals(7, response.getContentAsByteArray().length);
    }

    @Test
    void testBytesIsUnsetWhileAnAsyncResponseIsStillPending() throws Exception {
        MockHttpServletRequest request = mockRequest("/patients", "POST");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> req.startAsync();

        filter.doFilter(request, response, chain);

        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testFlushReachesTheResponseAtOnce() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] committedAfterFlush = new boolean[1];
        FilterChain chain = (req, res) -> {
            ServletOutputStream out = res.getOutputStream();
            out.write(new byte[3]);
            assertFalse(response.isCommitted());
            out.flush();
            committedAfterFlush[0] = response.isCommitted();
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertTrue(committedAfterFlush[0]);
    }

    @Test
    void testNonBlockingCallsReachTheContainerStream() throws Exception {
        ServletOutputStream containerStream = mock(ServletOutputStream.class);
        when(containerStream.isReady()).thenReturn(true);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(containerStream);
        when(response.getStatus()).thenReturn(200);
        WriteListener listener = mock(WriteListener.class);
        boolean[] ready = new boolean[1];
        FilterChain chain = (req, res) -> {
            ServletOutputStream out = res.getOutputStream();
            out.setWriteListener(listener);
            ready[0] = out.isReady();
            out.write(new byte[5], 1, 3);
            out.flush();
            out.close();
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertTrue(ready[0]);
        verify(containerStream).setWriteListener(listener);
        verify(containerStream).isReady();
        verify(containerStream).write(any(byte[].class), eq(1), eq(3));
        verify(containerStream).flush();
        verify(containerStream).close();
        verifyNoMoreInteractions(containerStream);
        assertEquals(Long.valueOf(3), sentEvent().getRequest().getBytes());
    }

    @Test
    void testLeavesBytesUnsetAfterSendErrorDiscardsAPartialBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.getOutputStream().write(new byte[100]);
            ((HttpServletResponse) res).sendError(409);
        };

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertNull(sentEvent().getRequest().getBytes());
        assertEquals(409, response.getStatus());
    }

    @Test
    void testLeavesBytesUnsetAfterSendErrorWithAMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).sendError(401, "Token is not valid");

        filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), response, chain);

        assertNull(sentEvent().getRequest().getBytes());
        assertEquals(401, response.getStatus());
        assertEquals("Token is not valid", response.getErrorMessage());
    }

    @Test
    void testLeavesBytesUnsetAndRethrowTheSameServletExceptionWhenTheChainThrows() {
        ServletException failure = new ServletException("handler failed");
        FilterChain chain = (req, res) -> {
            res.getOutputStream().write(new byte[100]);
            throw failure;
        };

        ServletException thrown = assertThrows(
            ServletException.class, () -> filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), new MockHttpServletResponse(), chain)
        );

        assertSame(failure, thrown);
        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testLeavesBytesUnsetAndRethrowTheSameRuntimeExceptionWhenTheChainThrows() {
        IllegalStateException failure = new IllegalStateException("handler failed");
        FilterChain chain = (req, res) -> {
            throw failure;
        };

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> filter.doFilter(mockRequest("/PIC-SURE/query/sync", "POST"), new MockHttpServletResponse(), chain)
        );

        assertSame(failure, thrown);
        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testReportsZeroBytesForAHeadRequestWhoseBodyTheContainerDiscards() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> res.getOutputStream().write(new byte[122]);

        filter.doFilter(mockRequest("/PIC-SURE/info", "HEAD"), response, chain);

        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
    }

    @Test
    void testLeavesBytesUnsetForAHeadRequestThatEndsInSendError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).sendError(404);

        filter.doFilter(mockRequest("/PIC-SURE/info", "HEAD"), response, chain);

        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testLeavesBytesUnsetForAHeadRequestThatStartsAsync() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/info", "HEAD");
        request.setAsyncSupported(true);
        FilterChain chain = (req, res) -> req.startAsync();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(sentEvent().getRequest().getBytes());
    }

    @Test
    void testReportsZeroBytesAfterSendRedirectDiscardsAPartialBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.getOutputStream().write(new byte[100]);
            ((HttpServletResponse) res).sendRedirect("/login");
        };

        filter.doFilter(mockRequest("/PIC-SURE/info", "GET"), response, chain);

        assertEquals(Long.valueOf(0), sentEvent().getRequest().getBytes());
        assertEquals("/login", response.getRedirectedUrl());
    }
}
