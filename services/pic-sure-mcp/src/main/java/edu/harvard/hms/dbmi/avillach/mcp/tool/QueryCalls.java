package edu.harvard.hms.dbmi.avillach.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/** Runs an open query call for a tool, logging a failure and mapping it to a {@link ToolFailure} that carries no downstream text. */
final class QueryCalls {

    private static final Logger log = LoggerFactory.getLogger(QueryCalls.class);

    private QueryCalls() {}

    /**
     * Runs the call. A failed response is logged with its status and the path only, never the body, and rethrown as
     * {@link ToolFailure#fromQueryDownstream}. A failure with no response is logged with the exception class and rethrown as
     * {@link ToolFailure#queryUnavailable()}.
     *
     * @param path the fixed gateway path, used for logging
     * @param call the query call
     * @param <T> the call's result type
     * @return the call's result
     * @throws ToolFailure when the call fails
     */
    static <T> T run(String path, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            log.warn("Open query call failed: status={} path={}", e.getStatusCode().value(), path);
            throw ToolFailure.fromQueryDownstream(e);
        } catch (RestClientException e) {
            log.warn("Open query call failed: {} path={}", e.getClass().getSimpleName(), path);
            throw ToolFailure.queryUnavailable();
        }
    }
}
