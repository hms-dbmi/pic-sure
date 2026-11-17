package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ServletOutputStream replacementStream;

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
    void theWriterIsTheWrappedResponsesOwnAndMarksTheCountUnknown() throws Exception {
        when(containerResponse.getWriter()).thenReturn(containerWriter);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        boolean before = wrapper.isByteCountUnknown();
        PrintWriter writer = wrapper.getWriter();

        assertThat(before).isFalse();
        assertThat(writer).isSameAs(containerWriter);
        assertThat(wrapper.isByteCountUnknown()).isTrue();
    }

    @Test
    void writerOutputReachesTheResponseAndIsNotCounted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);

        wrapper.getWriter().write("na\u00efve \u2603");
        wrapper.getWriter().flush();

        assertThat(response.getContentAsByteArray()).isEqualTo("na\u00efve \u2603".getBytes(StandardCharsets.UTF_8));
        assertThat(wrapper.getByteCount()).isZero();
        assertThat(wrapper.isByteCountUnknown()).isTrue();
    }

    @Test
    void theCountStaysUnknownAfterTheWriterEvenWhenTheResponseIsReset() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(response);
        wrapper.getWriter().write("abc");

        wrapper.reset();

        assertThat(wrapper.isByteCountUnknown()).isTrue();
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

    @Test
    void sendErrorWithAStatusReachesTheContainerAndMarksTheCountUnknown() throws Exception {
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        boolean before = wrapper.isByteCountUnknown();
        wrapper.sendError(400);

        assertThat(before).isFalse();
        verify(containerResponse).sendError(400);
        assertThat(wrapper.isByteCountUnknown()).isTrue();
    }

    @Test
    void sendErrorWithAMessageReachesTheContainerAndMarksTheCountUnknown() throws Exception {
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        wrapper.sendError(403, "rejected");

        verify(containerResponse).sendError(403, "rejected");
        assertThat(wrapper.isByteCountUnknown()).isTrue();
    }

    @Test
    void aSendErrorTheContainerRefusesLeavesTheCountStanding() throws Exception {
        doThrow(new IllegalStateException("committed")).when(containerResponse).sendError(500);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        assertThatThrownBy(() -> wrapper.sendError(500)).isInstanceOf(IllegalStateException.class).hasMessage("committed");
        assertThat(wrapper.isByteCountUnknown()).isFalse();
    }

    @Test
    void sendRedirectReachesTheContainerAndTheCountIsZero() throws Exception {
        when(containerResponse.getOutputStream()).thenReturn(containerStream);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);
        wrapper.getOutputStream().write(new byte[100]);

        wrapper.sendRedirect("/elsewhere");

        verify(containerResponse).sendRedirect("/elsewhere");
        assertThat(wrapper.getByteCount()).isZero();
        assertThat(wrapper.isByteCountUnknown()).isFalse();
    }

    @Test
    void aWriterAskedForAfterTheStreamStillThrowsFromTheWrappedResponse() throws Exception {
        IllegalStateException refusal = new IllegalStateException("getOutputStream() has already been called");
        when(containerResponse.getOutputStream()).thenReturn(containerStream);
        when(containerResponse.getWriter()).thenThrow(refusal);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        wrapper.getOutputStream();

        assertThatThrownBy(wrapper::getWriter).isSameAs(refusal);
        assertThat(wrapper.isByteCountUnknown()).isFalse();
    }

    @Test
    void aStreamAskedForAfterTheWriterStillThrowsFromTheWrappedResponse() throws Exception {
        IllegalStateException refusal = new IllegalStateException("getWriter() has already been called");
        when(containerResponse.getWriter()).thenReturn(containerWriter);
        when(containerResponse.getOutputStream()).thenThrow(refusal);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        wrapper.getWriter();

        assertThatThrownBy(wrapper::getOutputStream).isSameAs(refusal);
    }

    @Test
    void aStreamTheContainerNowRefusesIsNotServedFromTheCache() throws Exception {
        IllegalStateException refusal = new IllegalStateException("getWriter() has already been called");
        when(containerResponse.getOutputStream()).thenReturn(containerStream).thenThrow(refusal);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);

        wrapper.getOutputStream();

        assertThatThrownBy(wrapper::getOutputStream).isSameAs(refusal);
    }

    @Test
    void theCountingStreamIsReplacedWhenTheContainerReturnsADifferentStream() throws Exception {
        when(containerResponse.getOutputStream()).thenReturn(containerStream, replacementStream);
        ByteCountingResponseWrapper wrapper = new ByteCountingResponseWrapper(containerResponse);
        byte[] body = new byte[3];

        ServletOutputStream first = wrapper.getOutputStream();
        ServletOutputStream second = wrapper.getOutputStream();
        second.write(body, 0, 3);

        assertThat(second).isNotSameAs(first);
        verify(replacementStream).write(body, 0, 3);
        verifyNoInteractions(containerStream);
        assertThat(wrapper.getByteCount()).isEqualTo(3);
    }
}
