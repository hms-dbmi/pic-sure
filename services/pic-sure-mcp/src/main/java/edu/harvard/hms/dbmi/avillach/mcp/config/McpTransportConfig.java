package edu.harvard.hms.dbmi.avillach.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces the starter's stateless transport with one that carries {@link CallerHeaders#extract} as its context extractor, so every tool
 * call can read the caller's headers. The starter backs off when this bean exists.
 */
@Configuration(proxyBeanMethods = false)
public class McpTransportConfig {

    /**
     * The stateless Streamable HTTP transport, with {@link CallerHeaders#extract} as its context extractor.
     *
     * @param objectMapper the starter's MCP object mapper
     * @param serverProperties the starter's Streamable HTTP properties, whose endpoint is {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}
     * @return the transport the starter's router function binds
     */
    @Bean
    public WebMvcStatelessServerTransport picsureStatelessTransport(
        @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
        McpServerStreamableHttpProperties serverProperties
    ) {
        return WebMvcStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(objectMapper)).messageEndpoint(serverProperties.getMcpEndpoint())
            .contextExtractor(CallerHeaders::extract).build();
    }
}
