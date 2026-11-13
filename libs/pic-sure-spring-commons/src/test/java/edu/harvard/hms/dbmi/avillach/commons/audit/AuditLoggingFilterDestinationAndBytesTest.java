package edu.harvard.hms.dbmi.avillach.commons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

/** The destination address, destination port and response size each audit event reports. */
@ExtendWith(MockitoExtension.class)
class AuditLoggingFilterDestinationAndBytesTest {

    @Mock
    private LoggingClient client;

    @BeforeEach
    void enableClient() {
        when(client.isEnabled()).thenReturn(true);
    }

    private static MockHttpServletRequest requestArrivingOn(String localAddr, int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setLocalAddr(localAddr);
        request.setLocalPort(localPort);
        return request;
    }

    private RequestInfo emitted(
        AuditLoggingFilter filter, MockHttpServletRequest request, MockHttpServletResponse response, FilterChain chain
    ) throws Exception {
        filter.doFilter(request, response, chain);

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client).send(event.capture());
        return event.getValue().getRequest();
    }

    private RequestInfo emittedFor(FilterChain chain) throws Exception {
        AuditLoggingFilter filter = new AuditLoggingFilter(client, null, new AuditContext(), List.of());
        return emitted(filter, requestArrivingOn("10.0.0.7", 8080), new MockHttpServletResponse(), chain);
    }

    @Test
    void destinationIsTheLocalAddressAndPortTheRequestArrivedOn() throws Exception {
        RequestInfo info = emittedFor((req, res) -> {
        });

        assertThat(info.getDestIp()).isEqualTo("10.0.0.7");
        assertThat(info.getDestPort()).isEqualTo(8080);
    }

    @Test
    void aConfiguredDestinationReplacesTheLocalAddressAndPort() throws Exception {
        AuditLoggingFilter filter =
            new AuditLoggingFilter(client, null, new AuditContext(), List.of(), ClientTypeAllowlist.DEFAULT, "198.51.100.20", 443);

        RequestInfo info = emitted(filter, requestArrivingOn("10.0.0.7", 8080), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(info.getDestIp()).isEqualTo("198.51.100.20");
        assertThat(info.getDestPort()).isEqualTo(443);
    }

    @Test
    void aConfiguredAddressAloneLeavesThePortAsTheLocalPort() throws Exception {
        AuditLoggingFilter filter =
            new AuditLoggingFilter(client, null, new AuditContext(), List.of(), ClientTypeAllowlist.DEFAULT, "198.51.100.20", null);

        RequestInfo info = emitted(filter, requestArrivingOn("10.0.0.7", 8080), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(info.getDestIp()).isEqualTo("198.51.100.20");
        assertThat(info.getDestPort()).isEqualTo(8080);
    }

    @Test
    void bytesIsTheNumberOfBytesWrittenToTheOutputStream() throws Exception {
        byte[] body = "{\"count\":42}".getBytes(StandardCharsets.UTF_8);

        RequestInfo info = emittedFor((req, res) -> res.getOutputStream().write(body));

        assertThat(info.getBytes()).isEqualTo(body.length);
    }

    @Test
    void bytesAddsUpEveryChunkOfAStreamedBody() throws Exception {
        byte[] chunk = new byte[4096];

        RequestInfo info = emittedFor((req, res) -> {
            for (int i = 0; i < 50; i++) {
                res.getOutputStream().write(chunk, 0, chunk.length);
                res.flushBuffer();
            }
        });

        assertThat(info.getBytes()).isEqualTo(50L * 4096);
    }

    @Test
    void bytesIsTheEncodedSizeOfCharactersWrittenToTheWriter() throws Exception {
        String body = "{\"name\":\"Zoë ☃\"}";

        RequestInfo info = emittedFor((req, res) -> {
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write(body);
        });

        assertThat(info.getBytes()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(body.length());
    }

    @Test
    void bytesIsZeroWhenNoBodyIsWritten() throws Exception {
        RequestInfo info = emittedFor((req, res) -> ((HttpServletResponse) res).setStatus(204));

        assertThat(info.getBytes()).isZero();
        assertThat(info.getStatus()).isEqualTo(204);
    }

    @Test
    void bytesIsStillReportedWhenTheChainThrowsAfterWriting() throws Exception {
        AuditLoggingFilter filter = new AuditLoggingFilter(client, null, new AuditContext(), List.of());
        byte[] partial = new byte[10];

        assertThatThrownBy(() -> filter.doFilter(requestArrivingOn("10.0.0.7", 8080), new MockHttpServletResponse(), (req, res) -> {
            res.getOutputStream().write(partial);
            throw new IllegalStateException("upstream closed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("upstream closed");

        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(client).send(event.capture());
        assertThat(event.getValue().getRequest().getBytes()).isEqualTo(10);
    }

    @Test
    void theResponseTheClientReceivesIsUnchangedByTheFilter() throws Exception {
        byte[] body = new byte[20_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31);
        }
        FilterChain handler = (req, res) -> {
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(206);
            response.setContentType("application/octet-stream");
            response.setHeader("X-Result-Id", "abc-123");
            response.getOutputStream().write(body, 0, 12_000);
            response.getOutputStream().write(body, 12_000, 8_000);
        };
        MockHttpServletResponse unfiltered = new MockHttpServletResponse();
        handler.doFilter(new MockHttpServletRequest("POST", "/query/sync"), unfiltered);
        MockHttpServletResponse filtered = new MockHttpServletResponse();

        emitted(
            new AuditLoggingFilter(client, null, new AuditContext(), List.of()), requestArrivingOn("10.0.0.7", 8080), filtered, handler
        );

        assertThat(filtered.getContentAsByteArray()).isEqualTo(body).isEqualTo(unfiltered.getContentAsByteArray());
        assertThat(filtered.getStatus()).isEqualTo(unfiltered.getStatus());
        assertThat(filtered.getHeaderNames()).containsExactlyElementsOf(unfiltered.getHeaderNames());
        assertThat(filtered.getContentType()).isEqualTo(unfiltered.getContentType());
        assertThat(filtered.isCommitted()).isEqualTo(unfiltered.isCommitted());
    }
}
