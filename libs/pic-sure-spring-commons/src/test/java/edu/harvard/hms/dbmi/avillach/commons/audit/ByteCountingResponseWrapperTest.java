package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(MockitoExtension.class)
class ByteCountingResponseWrapperTest {

    @Mock
    private HttpServletResponse containerResponse;

    @Mock
    private ServletOutputStream containerStream;

    @Mock
    private PrintWriter containerWriter;

    @Mock
    private WriteListener writeListener;

    @Test
    void countIsZeroBeforeAnythingIsWritten() {
        assertThat(new ByteCountingResponseWrapper(new MockHttpServletResponse()).getByteCount()).isZero();
    }

    @Test
    void everyStreamWriteFormIsCountedAndPassedOnInOrder() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);

        ServletOutputStream stream = wrapper.getOutputStream();
        stream.write('a');
        stream.write(new byte[] {'b', 'c'});
        stream.write(new byte[] {'x', 'd', 'e', 'x'}, 1, 2);
        stream.print("fg");
        stream.println(7);

        assertThat(response.getContentAsByteArray()).isEqualTo("abcdefg7\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(wrapper.getByteCount()).isEqualTo(10);
    }

    @Test
    void streamWritesReachTheContainerStreamWithoutBeingHeld() throws Exception {
        when(containerResponse.getOutputStream()).thenReturn(containerStream);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);
        byte[] chunk = new byte[8192];

        wrapper.getOutputStream().write(chunk, 100, 5000);

        verify(containerStream).write(chunk, 100, 5000);
        verify(containerStream, never()).flush();
        verifyNoMoreInteractions(containerStream);
        assertThat(wrapper.getByteCount()).isEqualTo(5000);
    }

    @Test
    void flushCloseReadinessAndWriteListenerAreTheContainerStreams() throws Exception {
        when(containerResponse.getOutputStream()).thenReturn(containerStream);
        when(containerStream.isReady()).thenReturn(false, true);
        ServletOutputStream stream = new ByteCountingResponseWrapper(containerResponse).getOutputStream();

        stream.setWriteListener(writeListener);
        boolean firstReadiness = stream.isReady();
        boolean secondReadiness = stream.isReady();
        stream.flush();
        stream.close();

        assertThat(firstReadiness).isFalse();
        assertThat(secondReadiness).isTrue();
        InOrder order = inOrder(containerStream);
        order.verify(containerStream).setWriteListener(writeListener);
        order.verify(containerStream).flush();
        order.verify(containerStream).close();
    }

    @Test
    void aFailedStreamWriteIsNotCounted() throws Exception {
        when(containerResponse.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client went away");
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                throw new UnsupportedOperationException();
            }
        });
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);
        ServletOutputStream stream = wrapper.getOutputStream();

        assertThatThrownBy(() -> stream.write(new byte[64], 0, 64)).isInstanceOf(IOException.class).hasMessage("client went away");
        assertThat(wrapper.getByteCount()).isZero();
    }

    @Test
    void theSameStreamIsReturnedOnEveryCall() throws Exception {
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(new MockHttpServletResponse());

        assertThat(wrapper.getOutputStream()).isSameAs(wrapper.getOutputStream());
    }

    @Test
    void writerOutputIsCountedInTheResponseEncodingAndPassedOn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        String body = "naïve ☃ 😀";

        PrintWriter writer = wrapper.getWriter();
        writer.write(body);
        writer.print(12);
        writer.write('!');
        writer.write(new char[] {'a', 'b', 'c'}, 1, 2);
        writer.flush();

        byte[] expected = (body + "12!bc").getBytes(StandardCharsets.UTF_8);
        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(wrapper.getByteCount()).isEqualTo(expected.length);
    }

    @Test
    void writerOutputIsCountedInASingleByteEncoding() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("ISO-8859-1");
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);

        wrapper.getWriter().write("naïve");
        wrapper.getWriter().flush();

        assertThat(response.getContentAsByteArray()).hasSize(5);
        assertThat(wrapper.getByteCount()).isEqualTo(5);
    }

    @Test
    void aSurrogatePairSplitAcrossTwoWritesIsCountedOnce() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);

        wrapper.getWriter().write("a\ud83d");
        wrapper.getWriter().write("\ude00b");

        assertThat(wrapper.getByteCount()).isEqualTo("a😀b".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aBodyLargerThanTheCountingScratchSpaceIsCountedInFull() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        String body = "☃".repeat(10_000);

        wrapper.getWriter().write(body);

        assertThat(wrapper.getByteCount()).isEqualTo(30_000);
    }

    @Test
    void writerFlushCloseAndErrorStateAreTheContainerWriters() throws Exception {
        when(containerResponse.getWriter()).thenReturn(containerWriter);
        when(containerWriter.checkError()).thenReturn(true);
        PrintWriter writer = new ByteCountingResponseWrapper(containerResponse).getWriter();

        writer.write("abc");
        verify(containerWriter, never()).flush();
        writer.flush();
        boolean failed = writer.checkError();
        writer.close();

        assertThat(failed).isTrue();
        InOrder order = inOrder(containerWriter);
        order.verify(containerWriter).write("abc", 0, 3);
        order.verify(containerWriter).flush();
        order.verify(containerWriter).checkError();
        order.verify(containerWriter).close();
    }

    @Test
    void resettingTheBufferDiscardsTheCountWithTheBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        wrapper.getOutputStream().write(new byte[100]);

        wrapper.resetBuffer();
        wrapper.getOutputStream().write(new byte[7]);

        assertThat(response.getContentAsByteArray()).hasSize(7);
        assertThat(wrapper.getByteCount()).isEqualTo(7);
    }

    @Test
    void resettingTheResponseDiscardsTheCountWithTheBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        wrapper.getOutputStream().write(new byte[100]);

        wrapper.reset();

        assertThat(wrapper.getByteCount()).isZero();
    }

    @Test
    void aRefusedResetKeepsTheCount() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        wrapper.getOutputStream().write(new byte[100]);
        wrapper.flushBuffer();

        assertThatThrownBy(wrapper::resetBuffer).isInstanceOf(IllegalStateException.class);
        assertThat(wrapper.getByteCount()).isEqualTo(100);
    }

    @Test
    void writingDoesNotCommitTheResponseOrTouchItsHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);

        wrapper.getOutputStream().write(new byte[100]);

        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getHeaderNames()).isEmpty();
    }
}
