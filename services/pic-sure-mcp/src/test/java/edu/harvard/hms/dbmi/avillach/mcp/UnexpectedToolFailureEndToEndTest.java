package edu.harvard.hms.dbmi.avillach.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Posts a real {@code tools/call} for {@code get_concept} to {@code /mcp} while the dictionary client throws an exception the tool does not
 * map. Proves that through Spring AI's own transport the model sees only {@link ToolFailure#UNEXPECTED_MESSAGE} as an {@code isError}
 * result, the exception message appears nowhere in the response, and the call is audited once as an internal failure.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"PICSURE_GATEWAY_URL=http://gateway.test:8080", "MCP_SERVICE_TOKEN=test-mcp-token",
        "MCP_ADAPTER_BASE_URL=https://picsure.test"}
)
class UnexpectedToolFailureEndToEndTest {

    private static final String SECRET = "secret-xyz";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DictionaryClient dictionary;

    @MockitoBean
    private LoggingClient loggingClient;

    @Test
    void anUnmappedExceptionInAnAnnotatedToolReachesTheModelOnlyAsTheFixedMessage() throws Exception {
        when(dictionary.conceptDetail(any(), any(), any())).thenThrow(new IllegalStateException(SECRET));

        String response = RestClient.create("http://localhost:" + port).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer caller-token")
            .header("X-Request-Id", "req-unexpected").body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_concept",
                  "arguments":{"dataset":"phs1","conceptPath":"\\\\phs1\\\\sex\\\\"}}}""").retrieve().body(String.class);
        JsonNode json = objectMapper.readTree(response);
        JsonNode result = json.path("result");

        assertThat(json.has("error")).as(response).isFalse();
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").path(0).path("text").asText()).isEqualTo(ToolFailure.UNEXPECTED_MESSAGE);
        assertThat(response).doesNotContain(SECRET);

        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, times(1)).send(audit.capture(), isNull(), eq("req-unexpected"));
        assertThat(audit.getValue().getAction()).isEqualTo("concept.detail");
        assertThat(audit.getValue().getMetadata()).containsEntry("outcome", "failure");
        assertThat(audit.getValue().getError()).containsExactly(Map.entry("error_type", "internal"));
    }
}
