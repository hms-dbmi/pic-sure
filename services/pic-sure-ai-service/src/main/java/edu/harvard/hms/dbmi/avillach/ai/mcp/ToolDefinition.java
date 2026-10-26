package edu.harvard.hms.dbmi.avillach.ai.mcp;

/**
 * One tool the Bedrock model may call, in the shape the dispatch loop needs: enough to both advertise it to the model (see
 * {@code edu.harvard.hms.dbmi.avillach.ai.model}) and route a later call for it back to {@link McpToolGateway}. Deliberately not bound to
 * any MCP-protocol or Spring-AI type -- a real MCP client's {@code tools/list} response maps onto this record, but nothing here requires
 * it.
 *
 * @param name the tool name the model calls by, e.g. {@code search_concepts}
 * @param description what the tool does and when to call it, shown to the model
 * @param inputSchemaJson the tool's input JSON Schema, as a JSON string
 */
public record ToolDefinition(String name, String description, String inputSchemaJson) {
}
