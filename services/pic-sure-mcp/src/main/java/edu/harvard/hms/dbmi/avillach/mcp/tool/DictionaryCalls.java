package edu.harvard.hms.dbmi.avillach.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.function.Supplier;

/** Runs a dictionary call for a tool, logging a failure and mapping it to a {@link ToolFailure} that carries no downstream text. */
final class DictionaryCalls {

    private static final Logger log = LoggerFactory.getLogger(DictionaryCalls.class);

    private DictionaryCalls() {}

    /**
     * Runs the call. A failed response is logged with its status and the path only, never the body, and rethrown as
     * {@link ToolFailure#fromDownstream}. A failure with no response, or a response with no body, such as an empty 200 or a redirect the
     * gateway client does not follow, is logged without the body and rethrown as {@link ToolFailure#unavailable()}.
     *
     * @param path the fixed gateway path, used for logging
     * @param call the dictionary call
     * @param <T> the call's result type
     * @return the call's result, never null
     * @throws ToolFailure when the call fails or returns no body
     */
    static <T> T run(String path, Supplier<T> call) {
        return send(path, () -> requireBody(path, call.get()));
    }

    /**
     * Runs a lookup whose 404 means the dictionary does not know what was asked for. A 404 becomes an empty result; every other outcome is
     * handled as {@link #run} handles it.
     *
     * @param path the fixed gateway path, used for logging
     * @param call the dictionary lookup
     * @param <T> the lookup's result type
     * @return the result, or empty when the dictionary answered 404
     * @throws ToolFailure when the call fails or returns no body
     */
    static <T> Optional<T> lookup(String path, Supplier<T> call) {
        return send(path, () -> {
            try {
                return Optional.of(requireBody(path, call.get()));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            }
        });
    }

    private static <T> T send(String path, Supplier<T> call) {
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

    private static <T> T requireBody(String path, T body) {
        if (body == null) {
            log.warn("Dictionary call returned no body: path={}", path);
            throw ToolFailure.unavailable();
        }
        return body;
    }
}
