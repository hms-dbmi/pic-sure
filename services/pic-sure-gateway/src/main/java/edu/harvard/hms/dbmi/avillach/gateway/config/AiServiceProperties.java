package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code picsure.gateway.ai.*}: the shared secret {@code pic-sure-ai-service} presents as {@code X-PIC-SURE-AI-TOKEN} on its calls to
 * {@code /mcp}. Two values are accepted so the secret can be rotated without an outage: set the new value as {@code serviceToken}, keep the
 * old one as {@code previousServiceToken} until every {@code pic-sure-ai-service} instance has picked up the new one, then clear it.
 *
 * @param serviceToken the current secret, from {@code AI_SERVICE_TOKEN}; blank means none is configured
 * @param previousServiceToken the secret being rotated out, from {@code AI_SERVICE_TOKEN_PREVIOUS}; blank means none
 */
@ConfigurationProperties(prefix = "picsure.gateway.ai")
public record AiServiceProperties(String serviceToken, String previousServiceToken) {
}
