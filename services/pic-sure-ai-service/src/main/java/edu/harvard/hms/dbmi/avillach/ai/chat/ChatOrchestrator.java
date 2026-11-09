package edu.harvard.hms.dbmi.avillach.ai.chat;

/**
 * Runs one chat turn's Bedrock tool-use loop (see {@code ToolUseLoopService}) and produces the final response. The controller depends on
 * this interface, not the loop implementation, so the loop's Bedrock- and MCP-specific wiring stays out of the transport layer.
 */
public interface ChatOrchestrator {

    /**
     * Runs one chat turn to completion: sends the message to the model, dispatches any requested tool calls to the MCP gateway, and returns
     * once the model reaches {@code end_turn} or the iteration cap is hit.
     *
     * @param request the validated chat request
     * @param caller the caller's identity, replayed on every MCP call this turn makes
     * @return the chat response
     */
    ChatResponse handle(ChatRequest request, CallerContext caller);
}
