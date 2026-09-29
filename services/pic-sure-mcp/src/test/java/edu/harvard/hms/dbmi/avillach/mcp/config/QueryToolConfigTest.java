package edu.harvard.hms.dbmi.avillach.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.harvard.hms.dbmi.avillach.mcp.tool.CountResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Covers the query tool specifications' call handlers: binding happens before the tool bean is called, a result becomes
 * {@code structuredContent} plus one text block, a {@link ToolFailure} becomes an {@code isError} result with its message, and any other
 * exception becomes an {@code isError} result with a fixed message rather than the JSON-RPC error the stateless server would otherwise
 * send.
 */
class QueryToolConfigTest {

    private CountTool countTool;
    private SyncToolSpecification countSpec;
    private SyncToolSpecification crossCountSpec;

    @BeforeEach
    void setUp() {
        countTool = mock(CountTool.class);
        List<SyncToolSpecification> specs = new QueryToolConfig().queryToolSpecifications(countTool, mock(CrossCountTool.class));
        countSpec = specs.get(0);
        crossCountSpec = specs.get(1);
    }

    private CallToolResult call(SyncToolSpecification spec, Map<String, Object> arguments) {
        return spec.callHandler().apply(McpTransportContext.EMPTY, new McpSchema.CallToolRequest(spec.tool().name(), arguments));
    }

    @Test
    void registersBothToolsReadOnlyWithInputAndOutputSchemas() {
        assertThat(countSpec.tool().name()).isEqualTo("count_participants");
        assertThat(crossCountSpec.tool().name()).isEqualTo("cross_count");
        for (SyncToolSpecification spec : List.of(countSpec, crossCountSpec)) {
            McpSchema.ToolAnnotations annotations = spec.tool().annotations();
            assertThat(annotations.readOnlyHint()).isTrue();
            assertThat(annotations.destructiveHint()).isFalse();
            assertThat(annotations.idempotentHint()).isTrue();
            assertThat(annotations.openWorldHint()).isFalse();
            assertThat(spec.tool().inputSchema().defs()).containsKeys("Filter", "Subquery");
            assertThat(spec.tool().outputSchema()).containsKey("properties");
        }
    }

    @Test
    void aResultBecomesStructuredContentAndOneTextBlock() {
        when(countTool.handle(any(), any())).thenReturn(new CountResult("< 10", null, null, 10, true));

        CallToolResult result = call(countSpec, Map.of("query", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(Map.of("display", "< 10", "threshold", 10, "suppressed", true));
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
            McpSchema.TextContent.class, t -> assertThat(t.text()).isEqualTo("{\"display\":\"< 10\",\"threshold\":10,\"suppressed\":true}")
        );
    }

    @Test
    void aToolFailureBecomesAnIsErrorResultWithItsMessage() {
        when(countTool.handle(any(), any())).thenThrow(new ToolFailure("The query service is unavailable. Try again shortly."));

        CallToolResult result = call(countSpec, Map.of("query", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent()).isNull();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
            McpSchema.TextContent.class, t -> assertThat(t.text()).isEqualTo("The query service is unavailable. Try again shortly.")
        );
    }

    @Test
    void anUnexpectedExceptionBecomesAFixedIsErrorMessage() {
        when(countTool.handle(any(), any())).thenThrow(new IllegalStateException("SECRET-internal-detail"));

        CallToolResult result = call(countSpec, Map.of("query", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
            McpSchema.TextContent.class, t -> assertThat(t.text()).isEqualTo(QueryToolConfig.UNEXPECTED_FAILURE)
        );
    }

    @Test
    void aBindingFailureNeverReachesTheTool() {
        CallToolResult result = call(countSpec, Map.of("query", Map.of("phenotypicClause", Map.of("operator", "AND", "not", true))));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
            McpSchema.TextContent.class, t -> assertThat(t.text()).isEqualTo("Field 'not' is not part of this tool's input.")
        );
        verify(countTool, never()).handle(any(), any());
    }
}
