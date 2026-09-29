package edu.harvard.hms.dbmi.avillach.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PIC-SURE MCP server. The service answers MCP JSON-RPC over stateless Streamable HTTP on {@code /mcp} and reaches
 * PIC-SURE data only through the gateway's open channel.
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
