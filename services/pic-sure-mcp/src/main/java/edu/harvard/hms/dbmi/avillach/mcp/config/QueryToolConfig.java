package edu.harvard.hms.dbmi.avillach.mcp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountResult;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CrossCountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers the query tools as tool specification beans rather than {@code @McpTool} methods, because Spring AI's generated schema for a
 * recursive parameter leaves its {@code $defs} where the references cannot resolve. Each input schema is generated from the tool's root
 * input record instead, arguments are bound by {@link QueryBinder}, and the handler calls the tool bean's {@code handle} method so a proxy
 * around the bean sees every call.
 *
 * <p>An exception thrown from a specification's handler does not become an {@code isError} result: the stateless server turns it into a
 * JSON-RPC internal error carrying the exception's message. So the handler catches a {@link ToolFailure} and returns its message as an
 * {@code isError} result, and turns any other exception into a fixed message after logging it.
 */
@Configuration(proxyBeanMethods = false)
public class QueryToolConfig {

    /** The {@code isError} text for an exception that is not a {@link ToolFailure}. */
    public static final String UNEXPECTED_FAILURE = "The tool failed unexpectedly. Try again shortly.";

    private static final Logger log = LoggerFactory.getLogger(QueryToolConfig.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final McpJsonMapper SCHEMA_MAPPER = new JacksonMcpJsonMapper(MAPPER);

    /**
     * The query tool specifications, which the stateless server merges with the annotated tools.
     *
     * @param countTool the {@code count_participants} tool
     * @param crossCountTool the {@code cross_count} tool
     * @return the two query tool specifications
     */
    @Bean
    public List<SyncToolSpecification> queryToolSpecifications(CountTool countTool, CrossCountTool crossCountTool) {
        SyncToolSpecification count = SyncToolSpecification.builder()
            .tool(tool(CountTool.NAME, CountTool.TITLE, CountTool.DESCRIPTION, CountTool.Input.class, CountResult.class))
            .callHandler(
                (
                    context, request
                ) -> respond(CountTool.NAME, () -> countTool.handle(context, QueryBinder.bind(request.arguments(), CountTool.Input.class)))
            ).build();
        SyncToolSpecification crossCount = SyncToolSpecification.builder().tool(
            tool(CrossCountTool.NAME, CrossCountTool.TITLE, CrossCountTool.DESCRIPTION, CrossCountTool.Input.class, CrossCountResult.class)
        ).callHandler(
            (context, request) -> respond(
                CrossCountTool.NAME, () -> crossCountTool.handle(context, QueryBinder.bind(request.arguments(), CrossCountTool.Input.class))
            )
        ).build();
        return List.of(count, crossCount);
    }

    private static McpSchema.Tool tool(String name, String title, String description, Class<?> input, Class<?> output) {
        return McpSchema.Tool.builder().name(name).title(title).description(description)
            .inputSchema(SCHEMA_MAPPER, JsonSchemaGenerator.generateFromClass(input))
            .outputSchema(SCHEMA_MAPPER, JsonSchemaGenerator.generateFromType(output))
            .annotations(new McpSchema.ToolAnnotations(title, true, false, true, false, null)).build();
    }

    /**
     * Runs a tool call and shapes its outcome as a tool result: the record as {@code structuredContent} plus the same JSON as one text
     * block, a {@link ToolFailure} as an {@code isError} result carrying its message, and anything else as an {@code isError} result with
     * {@link #UNEXPECTED_FAILURE}.
     *
     * @param name the tool name, used for logging
     * @param call binds the arguments and calls the tool
     * @return the tool result
     */
    static CallToolResult respond(String name, Supplier<?> call) {
        try {
            Object result = call.get();
            Map<String, Object> structured = MAPPER.convertValue(result, new TypeReference<>() {});
            return CallToolResult.builder().addTextContent(MAPPER.writeValueAsString(result)).structuredContent(structured).isError(false)
                .build();
        } catch (ToolFailure e) {
            return error(e.getMessage());
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("Tool {} failed unexpectedly: {}", name, e.getClass().getName(), e);
            return error(UNEXPECTED_FAILURE);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
