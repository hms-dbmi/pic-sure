package edu.harvard.hms.dbmi.avillach.ai.model;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionToolCallbackTest {

    private final DefinitionToolCallback callback =
        new DefinitionToolCallback(new ToolDefinition("search_concepts", "search", "{\"type\":\"object\"}"));

    @Test
    void advertisesTheSourceDefinition() {
        org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo("search_concepts");
        assertThat(definition.description()).isEqualTo("search");
        assertThat(definition.inputSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void callIsNeverExpectedToRun() {
        assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(UnsupportedOperationException.class);
    }
}
