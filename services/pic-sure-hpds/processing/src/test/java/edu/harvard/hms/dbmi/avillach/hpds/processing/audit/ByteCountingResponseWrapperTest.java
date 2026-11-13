package edu.harvard.hms.dbmi.avillach.hpds.processing.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

class ByteCountingResponseWrapperTest {

    private HttpServletResponse response;
    private ByteCountingResponseWrapper wrapper;

    @BeforeEach
    void setUp() {
        response = mock(HttpServletResponse.class);
        wrapper = new ByteCountingResponseWrapper(response);
    }

    @Test
    void countIsKnownWhileTheBodyIsWrittenThroughTheWrapper() throws Exception {
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        assertEquals(OptionalLong.of(0), wrapper.getByteCount());
        wrapper.getOutputStream().write(new byte[100]);

        assertEquals(OptionalLong.of(100), wrapper.getByteCount());
    }

    @Test
    void countIsUnknownAfterSendErrorWithAStatus() throws Exception {
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        wrapper.getOutputStream().write(new byte[100]);

        wrapper.sendError(409);

        verify(response).sendError(409);
        assertEquals(OptionalLong.empty(), wrapper.getByteCount());
    }

    @Test
    void countIsUnknownAfterSendErrorWithAStatusAndMessage() throws Exception {
        wrapper.sendError(403, "User is deactivated");

        verify(response).sendError(403, "User is deactivated");
        assertEquals(OptionalLong.empty(), wrapper.getByteCount());
    }

    @Test
    void countStaysKnownWhenTheWrappedResponseRefusesTheError() throws Exception {
        IllegalStateException alreadyCommitted = new IllegalStateException("response is committed");
        doThrow(alreadyCommitted).when(response).sendError(500);

        assertSame(alreadyCommitted, assertThrows(IllegalStateException.class, () -> wrapper.sendError(500)));
        assertEquals(OptionalLong.of(0), wrapper.getByteCount());
    }

    @Test
    void writerAfterOutputStreamIsRefusedByTheWrappedResponse() throws Exception {
        IllegalStateException streamAlreadyTaken = new IllegalStateException("getOutputStream() has already been called");
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        when(response.getWriter()).thenThrow(streamAlreadyTaken);

        wrapper.getOutputStream();

        assertSame(streamAlreadyTaken, assertThrows(IllegalStateException.class, wrapper::getWriter));
    }

    @Test
    void outputStreamAfterWriterIsRefusedByTheWrappedResponse() throws Exception {
        IllegalStateException writerAlreadyTaken = new IllegalStateException("getWriter() has already been called");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(response.getOutputStream()).thenThrow(writerAlreadyTaken);

        wrapper.getWriter();

        assertSame(writerAlreadyTaken, assertThrows(IllegalStateException.class, wrapper::getOutputStream));
    }

    @Test
    void countIsZeroAfterSendRedirectDiscardsTheBuffer() throws Exception {
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        wrapper.getOutputStream().write(new byte[100]);

        wrapper.sendRedirect("/login");

        verify(response).sendRedirect("/login");
        assertEquals(OptionalLong.of(0), wrapper.getByteCount());
    }

    @Test
    void countIsUnknownWhenBothARedirectAndAnErrorWereSent() throws Exception {
        wrapper.sendRedirect("/login");
        wrapper.sendError(500);

        assertEquals(OptionalLong.empty(), wrapper.getByteCount());
    }

    @Test
    void countingStreamIsReusedWhileTheWrappedResponseReturnsTheSameStream() throws Exception {
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        assertSame(wrapper.getOutputStream(), wrapper.getOutputStream());
        verify(response, times(2)).getOutputStream();
    }

    @Test
    void countingStreamIsReplacedWhenTheWrappedResponseReturnsADifferentStream() throws Exception {
        ServletOutputStream first = mock(ServletOutputStream.class);
        ServletOutputStream second = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(first, second);

        ServletOutputStream overFirst = wrapper.getOutputStream();
        ServletOutputStream overSecond = wrapper.getOutputStream();
        overSecond.write(7);

        assertNotSame(overFirst, overSecond);
        verify(second).write(7);
        verifyNoInteractions(first);
        assertEquals(OptionalLong.of(1), wrapper.getByteCount());
    }

    @Test
    void countingWriterIsReusedWhileTheWrappedResponseReturnsTheSameWriter() throws Exception {
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertSame(wrapper.getWriter(), wrapper.getWriter());
        verify(response, times(2)).getWriter();
    }

    @Test
    void countingWriterIsReplacedWhenTheWrappedResponseReturnsADifferentWriter() throws Exception {
        StringWriter first = new StringWriter();
        StringWriter second = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(first), new PrintWriter(second));

        PrintWriter overFirst = wrapper.getWriter();
        PrintWriter overSecond = wrapper.getWriter();
        overSecond.write("ok");
        overSecond.flush();

        assertNotSame(overFirst, overSecond);
        assertEquals("ok", second.toString());
        assertEquals("", first.toString());
        assertEquals(OptionalLong.of(2), wrapper.getByteCount());
    }
}
