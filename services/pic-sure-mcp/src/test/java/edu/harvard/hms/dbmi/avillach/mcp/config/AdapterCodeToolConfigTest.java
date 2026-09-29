package edu.harvard.hms.dbmi.avillach.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterSetup;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Covers the {@code get_adapter_code} specification: its schemas and annotations, and the shape of a result, which is
 * {@code structuredContent} plus a setup block and a code block annotated {@code audience: ["user"]}.
 */
class AdapterCodeToolConfigTest {

    private static final AdapterCodeResult RESULT = new AdapterCodeResult(
        "python", new AdapterCodeResult.Requires("picsure", "3.0.0", "Python >= 3.10"), "python -c \"check\"",
        "pip install 'picsure>=3.0.0'", "import os\nimport picsure\n", null
    );

    private AdapterCodeTool adapterCodeTool;
    private SyncToolSpecification spec;

    @BeforeEach
    void setUp() {
        adapterCodeTool = mock(AdapterCodeTool.class);
        spec = new AdapterCodeToolConfig().adapterCodeToolSpecifications(adapterCodeTool).get(0);
    }

    private CallToolResult call() {
        return spec.callHandler().apply(McpTransportContext.EMPTY, new McpSchema.CallToolRequest(AdapterCodeTool.NAME, Map.of()));
    }

    @Test
    void registersTheToolReadOnlyWithTheQuerySchemaAndTheResultEnums() throws Exception {
        assertThat(spec.tool().name()).isEqualTo("get_adapter_code");
        assertThat(spec.tool().annotations().readOnlyHint()).isTrue();
        assertThat(spec.tool().annotations().destructiveHint()).isFalse();
        assertThat(spec.tool().inputSchema().defs()).containsKeys("Filter", "Subquery");
        String input = new ObjectMapper().writeValueAsString(spec.tool().inputSchema());
        assertThat(input).doesNotContain("\"not\"").contains("[\"count\",\"cross_count\",\"participant\",\"timestamp\"]")
            .contains("[\"python\",\"r\",\"bash\"]").contains("\"checkConcepts\"");
        String output = new ObjectMapper().writeValueAsString(spec.tool().outputSchema());
        assertThat(output).contains("\"package\"", "\"minVersion\"", "\"runtime\"", "\"warnings\"");
    }

    @Test
    void aResultIsStructuredContentASetupBlockAndACodeBlockForTheUser() {
        when(adapterCodeTool.handle(any(), any())).thenReturn(RESULT);

        CallToolResult result = call();

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(
            Map.of(
                "language", "python", "requires", Map.of("package", "picsure", "minVersion", "3.0.0", "runtime", "Python >= 3.10"), "check",
                "python -c \"check\"", "install", "pip install 'picsure>=3.0.0'", "code", "import os\nimport picsure\n"
            )
        );
        assertThat(result.content()).hasSize(2);
        McpSchema.TextContent setup = (McpSchema.TextContent) result.content().get(0);
        McpSchema.TextContent code = (McpSchema.TextContent) result.content().get(1);
        assertThat(setup.text()).isEqualTo(RESULT.setupText()).contains("Check: python -c \"check\"")
            .contains("Do not run it unless the user asks.");
        assertThat(setup.annotations()).isNull();
        assertThat(code.text()).isEqualTo("import os\nimport picsure\n");
        assertThat(code.annotations().audience()).containsExactly(McpSchema.Role.USER);
    }

    @Test
    void aResultWithNoConnectorVersionLeavesMinVersionOut() {
        AdapterCodeResult bash = new AdapterCodeResult(
            "bash", new AdapterCodeResult.Requires("curl and jq", null, "bash"), "jq --version", "Install curl and jq",
            "#!/usr/bin/env bash\n", null
        );
        when(adapterCodeTool.handle(any(), any())).thenReturn(bash);

        CallToolResult result = call();

        assertThat(((Map<?, ?>) result.structuredContent()).get("requires")).isEqualTo(Map.of("package", "curl and jq", "runtime", "bash"));
        assertThat(bash.setupText()).startsWith("Requires curl and jq (bash).\n");
    }

    @Test
    void aToolFailureIsAnIsErrorResult() {
        when(adapterCodeTool.handle(any(), any())).thenThrow(new ToolFailure("Code in bash is not available on this server."));

        CallToolResult result = call();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(
            McpSchema.TextContent.class, t -> assertThat(t.text()).isEqualTo("Code in bash is not available on this server.")
        );
    }

    @Test
    void theSetupBeanCopiesTheAdapterProperties() {
        McpProperties properties =
            new McpProperties("http://gateway", "token", new McpProperties.Adapter("https://picsure.test", true, true, "3.1.0", null));

        assertThat(new AdapterCodeToolConfig().adapterSetup(properties))
            .isEqualTo(new AdapterSetup("https://picsure.test", true, true, "3.1.0", "v3.0.0"));
    }

    @Test
    void theSetupBeanDropsTheBaseUrlTrailingSlashOnce() {
        McpProperties properties =
            new McpProperties("http://gateway", "token", new McpProperties.Adapter("https://aio.example.org/", false, false, null, null));

        assertThat(new AdapterCodeToolConfig().adapterSetup(properties).baseUrl()).isEqualTo("https://aio.example.org");
        assertThat(new AdapterSetup("http://localhost//", false, false, "3.0.0", "v3.0.0").baseUrl()).isEqualTo("http://localhost");
    }
}
