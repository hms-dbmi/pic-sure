package edu.harvard.hms.dbmi.avillach.mcp.caller;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;
import java.util.Map;

/**
 * The inbound headers a tool replays on every loop-back call to the gateway. The transport's context extractor reads them off the MCP
 * request with {@link #extract(ServerRequest)}, a tool reads them back with {@link #from(McpTransportContext)}, and a gateway client writes
 * them onto its outbound request with {@link #applyTo(HttpHeaders)}. The gateway client adds the MCP service credential itself.
 *
 * <p> {@link #toString()} never prints the {@code Authorization} value or the API key, so the record is safe to log. CR and LF are stripped
 * from the request ID and the forwarded-for chain, so neither can split a log line.
 *
 * @param authorization the inbound {@code Authorization} header, or null
 * @param apiKey the inbound {@code X-PICSURE-API-Key} header, or null
 * @param requestId the inbound {@code X-Request-Id} header, or null
 * @param forwardedFor every inbound {@code X-Forwarded-For} value joined with {@code ", "}, or null
 * @param userId the {@code X-User-Id} the gateway set after verifying the caller, or null. Read only for audit, never replayed
 */
public record CallerHeaders(String authorization, String apiKey, String requestId, String forwardedFor, String userId) {

    /**
     * Strips CR and LF from the request ID and the forwarded-for chain.
     *
     * @param authorization the inbound {@code Authorization} header, or null
     * @param apiKey the inbound {@code X-PICSURE-API-Key} header, or null
     * @param requestId the inbound {@code X-Request-Id} header, or null
     * @param forwardedFor the inbound {@code X-Forwarded-For} chain, or null
     * @param userId the inbound {@code X-User-Id} header, or null
     */
    public CallerHeaders {
        requestId = stripLineBreaks(requestId);
        forwardedFor = stripLineBreaks(forwardedFor);
        userId = stripLineBreaks(userId);
    }

    /**
     * Creates headers with no user ID.
     *
     * @param authorization the inbound {@code Authorization} header, or null
     * @param apiKey the inbound {@code X-PICSURE-API-Key} header, or null
     * @param requestId the inbound {@code X-Request-Id} header, or null
     * @param forwardedFor the inbound {@code X-Forwarded-For} chain, or null
     */
    public CallerHeaders(String authorization, String apiKey, String requestId, String forwardedFor) {
        this(authorization, apiKey, requestId, forwardedFor, null);
    }

    /** Transport context key the extractor stores this record under. */
    public static final String KEY = CallerHeaders.class.getName();

    /** Header carrying the PIC-SURE API key on sites that enforce one. */
    public static final String API_KEY_HEADER = "X-PICSURE-API-Key";

    /** Header carrying the request ID that traces one MCP call end to end. */
    public static final String REQUEST_ID_HEADER = RequestIdFilter.HEADER;

    /** Header carrying the client address chain. */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /** Header carrying the verified user ID the gateway sets. */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** MDC key the request ID is bound to, shared with the rest of the reactor. */
    public static final String MDC_KEY = RequestIdFilter.MDC_KEY;

    private static final CallerHeaders NONE = new CallerHeaders(null, null, null, null);

    private static final Logger log = LoggerFactory.getLogger(CallerHeaders.class);

    /**
     * Reads the caller headers off an inbound MCP request.
     *
     * @param request the inbound servlet functional request
     * @return a transport context holding one {@link CallerHeaders} under {@link #KEY}
     */
    public static McpTransportContext extract(ServerRequest request) {
        ServerRequest.Headers inbound = request.headers();
        CallerHeaders headers = new CallerHeaders(
            inbound.firstHeader(HttpHeaders.AUTHORIZATION), inbound.firstHeader(API_KEY_HEADER), inbound.firstHeader(REQUEST_ID_HEADER),
            joined(inbound.header(FORWARDED_FOR_HEADER)), inbound.firstHeader(USER_ID_HEADER)
        );
        log.debug("Extracted caller headers {}", headers);
        return McpTransportContext.create(Map.of(KEY, headers));
    }

    /**
     * Reads the record back out of a tool call's transport context.
     *
     * @param context the transport context Spring AI passes to the tool method
     * @return the stored headers, or an all-null record when the extractor did not run
     */
    public static CallerHeaders from(McpTransportContext context) {
        Object value = context == null ? null : context.get(KEY);
        return value instanceof CallerHeaders headers ? headers : NONE;
    }

    /**
     * Writes the caller's headers onto an outbound gateway request. A header the caller did not send is not written.
     *
     * @param headers the outbound request headers
     */
    public void applyTo(HttpHeaders headers) {
        setIfPresent(headers, HttpHeaders.AUTHORIZATION, authorization);
        setIfPresent(headers, API_KEY_HEADER, apiKey);
        setIfPresent(headers, REQUEST_ID_HEADER, requestId);
        setIfPresent(headers, FORWARDED_FOR_HEADER, forwardedFor);
        log.debug("Replaying caller headers {}", this);
    }

    /**
     * Binds the request ID to {@code MDC[requestId]} until the returned scope closes, so every log line of the tool call carries it.
     * Closing the scope restores whatever value the key held before. When the caller sent no request ID the MDC is left as it is.
     *
     * @return a scope to close when the tool call ends
     */
    public MdcScope bindRequestId() {
        if (requestId == null || requestId.isBlank()) {
            return () -> {
            };
        }
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        };
    }

    /**
     * Describes the record without its credentials: the {@code Authorization} value and the API key are reported only as present or absent.
     *
     * @return a string safe to write to a log
     */
    @Override
    public String toString() {
        return "CallerHeaders[authorization=" + presence(authorization) + ", apiKey=" + presence(apiKey) + ", requestId=" + requestId
            + ", forwardedFor=" + forwardedFor + ", userId=" + userId + "]";
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static String stripLineBreaks(String value) {
        return value == null ? null : value.replace("\r", "").replace("\n", "");
    }

    private static String presence(String value) {
        return value == null || value.isBlank() ? "<absent>" : "<redacted>";
    }

    /** A request ID binding in the MDC, undone by {@link #close()}. */
    @FunctionalInterface
    public interface MdcScope extends AutoCloseable {

        /** Restores the MDC key to the value it held before the binding. */
        @Override
        void close();
    }
}
