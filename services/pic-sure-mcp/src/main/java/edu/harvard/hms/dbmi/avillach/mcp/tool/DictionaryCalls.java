package edu.harvard.hms.dbmi.avillach.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/** Runs a dictionary call for a tool, logging a failure and mapping it to a {@link ToolFailure} that carries no downstream text. */
final class DictionaryCalls {

    private static final Logger log = LoggerFactory.getLogger(DictionaryCalls.class);

    private DictionaryCalls() {}

    /**
     * Runs the call. A failed response is logged with its status and the path only, never the body, and rethrown as
     * {@link ToolFailure#fromDownstream}. A failure with no response is logged with the exception class and rethrown as
     * {@link ToolFailure#unavailable()}.
     *
     * @param path the fixed gateway path, used for logging
     * @param call the dictionary call
     * @param <T> the call's result type
     * @return the call's result
     * @throws ToolFailure when the call fails
     */
    static <T> T run(String path, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            log.warn("Dictionary call failed: status={} path={}", e.getStatusCode().value(), path);
            throw ToolFailure.fromDownstream(e);
        } catch (RestClientException e) {
            log.warn("Dictionary call failed: {} path={}", e.getClass().getSimpleName(), path);
            throw ToolFailure.unavailable();
        }
    }
}
