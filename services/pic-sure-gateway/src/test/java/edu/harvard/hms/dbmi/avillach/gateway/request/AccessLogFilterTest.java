package edu.harvard.hms.dbmi.avillach.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("gateway.access");

    @BeforeEach
    void attachAppender() {
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void logsOneLinePerRequestWithMethodPathStatusAndDuration() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).startsWith("POST /query/sync 200 ").endsWith("ms");
    }

    @Test
    void skipsActuatorEndpointsSoProbesAndScrapesDoNotFloodTheLog() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).isEmpty();
    }

    @Test
    void logsEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        try {
            filter.doFilter(request, response, (req, resp) -> {
                throw new RuntimeException("upstream exploded");
            });
        } catch (Exception expected) {
            // the filter must re-throw; the access line must still be written
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).startsWith("GET /query/boom 500 ");
    }
}
