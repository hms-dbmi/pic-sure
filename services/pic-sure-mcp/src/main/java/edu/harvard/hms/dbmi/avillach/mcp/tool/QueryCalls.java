package edu.harvard.hms.dbmi.avillach.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Function;
import java.util.function.Supplier;

/** Runs a query call for a tool, logging a failure and mapping it to a {@link ToolFailure} that carries no downstream text. */
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
        return run(path, call, ToolFailure::fromQueryDownstream, ToolFailure::queryUnavailable);
    }

    /**
     * Runs the call like {@link #run(String, Supplier)}, with the failure messages chosen by the caller.
     *
     * @param path the fixed gateway path or a short label, used for logging
     * @param call the query call
     * @param onResponseError maps a failed response to the failure to throw
     * @param onNoResponse the failure to throw when there was no response
     * @param <T> the call's result type
     * @return the call's result
     * @throws ToolFailure when the call fails
     */
    static <T> T run(
        String path, Supplier<T> call, Function<RestClientResponseException, ToolFailure> onResponseError,
        Supplier<ToolFailure> onNoResponse
    ) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            log.warn("Query call failed: status={} path={}", e.getStatusCode().value(), path);
            throw onResponseError.apply(e);
        } catch (RestClientException e) {
            log.warn("Query call failed: {} path={}", e.getClass().getSimpleName(), path);
            throw onNoResponse.get();
        }
    }
}
