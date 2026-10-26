package edu.harvard.hms.dbmi.avillach.ai.mcp.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

class MockMcpToolGatewayTest {

    private final MockMcpToolGateway gateway = new MockMcpToolGateway(new ObjectMapper());
    private final CallerContext caller = new CallerContext("Bearer a", null, "user-a");

    @Test
    void listsEveryToolIncludingTheProposalTool() {
        List<String> names = gateway.listTools().stream().map(ToolDefinition::name).toList();

        assertThat(names).containsExactly("search_concepts", "list_facets", "count_participants", "cross_count", "propose_query");
    }

    @Test
    void everyListedToolReturnsASuccessfulJsonResult() throws Exception {
        for (ToolDefinition tool : gateway.listTools()) {
            ToolResult result = gateway.callTool(tool.name(), "{}", caller);

            assertThat(result.error()).as(tool.name()).isFalse();
            assertThat(new ObjectMapper().readTree(result.content()).isObject()).as(tool.name()).isTrue();
        }
    }

    @Test
    void searchConceptsEmbedsAnOpaqueMarkerNeverTheRawUserId() {
        CallerContext emailUser = new CallerContext("Bearer a", null, "jane@example.org");

        String content = gateway.callTool("search_concepts", "{}", emailUser).content();

        assertThat(content).contains("caller-").doesNotContain("jane@example.org");
        assertThat(content).isNotEqualTo(gateway.callTool("search_concepts", "{}", new CallerContext("Bearer a", null, "other")).content());
    }

    @Test
    void searchConceptsFallsBackToADigestOfTheTokenAndDiffersPerCaller() {
        String first = gateway.callTool("search_concepts", "{}", new CallerContext("Bearer a", null, null)).content();
        String second = gateway.callTool("search_concepts", "{}", new CallerContext("Bearer b", null, " ")).content();
        String none = gateway.callTool("search_concepts", "{}", new CallerContext(null, null, null)).content();

        assertThat(first).contains("caller-").isNotEqualTo(second);
        assertThat(none).contains("caller-");
        assertThat(first).doesNotContain("Bearer a");
    }

    @Test
    void unknownToolIsAFailureResult() {
        ToolResult result = gateway.callTool("nope", "{}", caller);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("Unknown tool: nope");
    }
}
