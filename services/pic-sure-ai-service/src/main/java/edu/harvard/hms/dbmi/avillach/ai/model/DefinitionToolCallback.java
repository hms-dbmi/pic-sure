package edu.harvard.hms.dbmi.avillach.ai.model;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Advertises one {@link edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition} to Spring AI as a {@link ToolCallback} purely for its schema.
 * {@link #call} must never run: this service always sets {@code internal-tool-execution-enabled: false} (see {@code application.yml})
 * precisely so the dispatch loop, not Spring AI, decides where each tool call goes -- Bedrock's own tool-call request is what the loop
 * reads back, never this method's return value.
 */
final class DefinitionToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    DefinitionToolCallback(edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition source) {
        this.definition =
            ToolDefinition.builder().name(source.name()).description(source.description()).inputSchema(source.inputSchemaJson()).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException(
            "internal-tool-execution-enabled is false; the dispatch loop must call the tool, not Spring AI"
        );
    }
}
