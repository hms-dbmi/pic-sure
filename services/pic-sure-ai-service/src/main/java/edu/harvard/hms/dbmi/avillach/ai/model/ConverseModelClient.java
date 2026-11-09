package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;

/**
 * Everything the rest of the service needs from the reasoning model, expressed in plain local types so nothing outside this package ever
 * imports Bedrock- or Spring-AI-model-specific types (enforced by {@code ArchitectureTest}). Named for the wire shape, not a vendor: both
 * implementations speak Amazon Bedrock's Converse API shape ({@code messages}/{@code toolConfig} in,
 * {@code output.message}/{@code stopReason} out) -- see {@link ConverseSdkModelClient} (real AWS Bedrock, SigV4/IAM) and
 * {@link ConverseHttpModelClient} (the same shape over a plain bearer token, for self-hosted deployments pointing at a different vendor via
 * a translating proxy). Swapping to a genuinely different shape later -- e.g. calling Claude directly via Anthropic's Messages API -- is a
 * third implementation of this one interface, not a change anywhere else.
 */
public interface ConverseModelClient {

    /**
     * Sends the full running conversation to the model and returns its next turn. The Converse API is stateless, so {@code history} must
     * carry everything the model needs to have "remembered".
     *
     * @param systemPrompt the system prompt for this turn (tool-result-is-data hardening, etc.)
     * @param history every prior turn of this conversation, oldest first
     * @param tools the tools to advertise to the model this turn
     * @return the model's next turn
     * @throws ModelUnavailableException if the model call itself fails (timeout, overload, etc.) -- never thrown for a tool-call request,
     *         which is a normal, successful turn
     */
    ModelTurnResult send(String systemPrompt, List<ConversationEntry> history, List<ToolDefinition> tools);
}
