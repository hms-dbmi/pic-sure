package edu.harvard.hms.dbmi.avillach.mcp.config;

import edu.harvard.hms.dbmi.avillach.mcp.codegen.AdapterSetup;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.AdapterCodeTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers {@code get_adapter_code} as a tool specification bean, the same way {@link QueryToolConfig} registers the count tools, and
 * builds the {@link AdapterSetup} the generators read from {@code picsure.mcp.adapter}. A success carries the result as
 * {@code structuredContent} and two text blocks: the setup, and the code annotated for the user's eyes.
 */
@Configuration(proxyBeanMethods = false)
public class AdapterCodeToolConfig {

    /**
     * The deployment's connection details and adapter versions, from {@code picsure.mcp.adapter}.
     *
     * @param properties the service properties
     * @return the adapter setup
     */
    @Bean
    public AdapterSetup adapterSetup(McpProperties properties) {
        McpProperties.Adapter adapter = properties.adapter();
        return new AdapterSetup(
            adapter.baseUrl(), adapter.includeConsents(), adapter.supportsGenomic(), adapter.pythonMinVersion(), adapter.rTag()
        );
    }

    /**
     * The {@code get_adapter_code} specification, which the stateless server merges with the other tools.
     *
     * @param adapterCodeTool the tool
     * @return the one specification, in a list as the server expects
     */
    @Bean
    public List<SyncToolSpecification> adapterCodeToolSpecifications(AdapterCodeTool adapterCodeTool) {
        SyncToolSpecification spec = SyncToolSpecification.builder()
            .tool(
                QueryToolConfig.tool(
                    AdapterCodeTool.NAME, AdapterCodeTool.TITLE, AdapterCodeTool.DESCRIPTION, AdapterCodeTool.Input.class,
                    AdapterCodeResult.class
                )
            ).callHandler(
                (context, request) -> QueryToolConfig.respond(
                    AdapterCodeTool.NAME, () -> adapterCodeTool.handle(context, request.arguments()), AdapterCodeToolConfig::content
                )
            ).build();
        return List.of(spec);
    }

    /**
     * The content blocks of a success: the setup lines, then the code alone, annotated with {@code audience: ["user"]} so a client that
     * honors it shows the code to the person rather than feeding it to the model.
     *
     * @param result the tool result
     * @return the setup block and the code block
     */
    static List<McpSchema.Content> content(AdapterCodeResult result) {
        return List.of(
            new McpSchema.TextContent(result.setupText()),
            new McpSchema.TextContent(new McpSchema.Annotations(List.of(McpSchema.Role.USER), null), result.code())
        );
    }
}
