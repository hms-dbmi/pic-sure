package edu.harvard.hms.dbmi.avillach.ai.model;

/**
 * The model itself failed to respond -- a timeout, overload, or any other Bedrock-call-level failure. Deliberately distinct from a tool/MCP
 * lookup failure (see {@code ToolResult#failure}), which never throws and is instead folded back into the conversation as an ordinary tool
 * result: Story 3's AC requires the two failure modes to produce different user-facing errors, and this exception type is what the dispatch
 * loop catches to tell them apart.
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
