package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code picsure.gateway.mcp.*}: the shared secret {@code pic-sure-mcp} presents as {@code X-PIC-SURE-MCP-TOKEN} on its loop-back
 * calls through the gateway. Two values are accepted so the secret can be rotated without an outage: set the new value as
 * {@code serviceToken}, keep the old one as {@code previousServiceToken} until every {@code pic-sure-mcp} instance has picked up the new
 * one, then clear it.
 *
 * @param serviceToken the current secret, from {@code MCP_SERVICE_TOKEN}; blank means none is configured
 * @param previousServiceToken the secret being rotated out, from {@code MCP_SERVICE_TOKEN_PREVIOUS}; blank means none
 */
@ConfigurationProperties(prefix = "picsure.gateway.mcp")
public record McpProperties(String serviceToken, String previousServiceToken) {
}
