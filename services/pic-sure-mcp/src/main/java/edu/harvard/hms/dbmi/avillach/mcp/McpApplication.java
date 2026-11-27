package edu.harvard.hms.dbmi.avillach.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PIC-SURE MCP server. The service answers MCP JSON-RPC over stateless Streamable HTTP on {@code /mcp} and reaches
 * PIC-SURE data through the gateway's open channel, plus count-only queries on the authorized channel sent with the caller's own token.
 */
@SpringBootApplication
public class McpApplication {

    /**
     * Starts the MCP server.
     *
     * @param args command line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}
