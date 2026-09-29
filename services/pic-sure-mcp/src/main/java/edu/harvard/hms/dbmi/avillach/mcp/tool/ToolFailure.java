package edu.harvard.hms.dbmi.avillach.mcp.tool;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * A tool call that failed with a message the model can act on. Spring AI reports the innermost cause's message as the {@code isError} text,
 * so this exception has a message-only constructor and never a cause: a cause would carry the downstream body, headers, or a stack trace to
 * the model.
 */
public class ToolFailure extends RuntimeException {

    /**
     * Creates a failure.
     *
     * @param message the short, model-facing message
     */
    public ToolFailure(String message) {
        super(message, null, false, false);
    }

    /**
     * Maps a failed dictionary response to a short message. The message depends only on the status code; the body and headers are never
     * read.
     *
     * @param e the downstream failure
     * @return a failure whose message names what went wrong
     */
    public static ToolFailure fromDownstream(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.value() == 404) {
            return new ToolFailure("The dictionary endpoint was not found.");
        }
        if (status.value() == 401 || status.value() == 403) {
            return new ToolFailure("The caller is not authorized for the dictionary.");
        }
        if (status.is5xxServerError()) {
            return new ToolFailure("The dictionary is unavailable. Try again shortly.");
        }
        return new ToolFailure("The dictionary rejected the request.");
    }

    /**
     * The failure for a concept lookup that found no concept. Only {@code get_concept} uses it, since it is the one tool that can name a
     * concept path the model can correct.
     *
     * @return a failure pointing the model to {@code search_concepts}
     */
    public static ToolFailure conceptNotFound() {
        return new ToolFailure("Concept path not found. Call search_concepts to find valid paths.");
    }

    /**
     * The failure for a downstream call that never produced a response, such as a refused connection or a timeout.
     *
     * @return a failure saying the dictionary is unavailable
     */
    public static ToolFailure unavailable() {
        return new ToolFailure("The dictionary is unavailable. Try again shortly.");
    }
}
