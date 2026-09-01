package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;

class AuditFilterConfigLoggingClientTest {

    @Test
    void loggingClientUsesTheDeployedApiClientType() {
        LoggingClient expected = LoggingClient.noOp();

        try (MockedStatic<LoggingClientFactory> loggingClientFactory = Mockito.mockStatic(LoggingClientFactory.class)) {
            loggingClientFactory.when(() -> LoggingClientFactory.create("api")).thenReturn(expected);

            LoggingClient client = new AuditFilterConfig().loggingClient();

            assertThat(client).isSameAs(expected);
            loggingClientFactory.verify(() -> LoggingClientFactory.create("api"));
        }
    }
}
