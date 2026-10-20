package edu.harvard.hms.dbmi.avillach.query.routing;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

/**
 * HTTP client for Rust HPDS on its own connection pool. It sends the same requests {@link ResourceWebClient} sends Java HPDS, with the Rust
 * service token on the sync query and no token on search, and never throws for a status: every call returns a {@link RustOutcome}, either
 * the answer with its status, body and {@code queryMetadata} header, or the failure classified as a connect error, a timeout, an exhausted
 * connection pool, another I/O error, or an error inside the client itself (a request that cannot be built or written, a shut-down client).
 * Each call reads at most the given number of body bytes plus one, and marks a longer body truncated. Nothing here logs, because a response
 * body or an exception message can carry patient data.
 */
@Component
public class RustHpdsClient {

    /** The read limit that reads the whole body. */
    public static final long UNLIMITED = Long.MAX_VALUE;

    private final RestClient http;

    public RustHpdsClient(@Qualifier("hpdsRsClient") RestClient http) {
        this.http = http;
    }

    /**
     * Runs a query on Rust HPDS's synchronous route.
     *
     * @param apiBase the Rust API base URL, the API path included
     * @param token the Rust service token, or null or blank for none
     * @param req the scoped query envelope, the same object Java HPDS receives
     * @param requestSource the {@code request-source} header to forward, or null
     * @param maxBodyBytes the most body bytes kept, or {@link #UNLIMITED}
     * @return the answer or the failure
     */
    public RustOutcome querySync(String apiBase, String token, QueryRequest req, String requestSource, long maxBodyBytes) {
        return exchange(() -> http.post().uri(apiBase + "/query/sync").contentType(MediaType.APPLICATION_JSON).headers(h -> {
            if (token != null && !token.isBlank()) {
                h.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            if (requestSource != null) {
                h.add("request-source", requestSource);
            }
        }).body(req), maxBodyBytes);
    }

    /**
     * Searches concepts on Rust HPDS.
     *
     * @param apiBase the Rust API base URL, the API path included
     * @param req the search envelope Java HPDS receives
     * @param maxBodyBytes the most body bytes kept, or {@link #UNLIMITED}
     * @return the answer or the failure
     */
    public RustOutcome search(String apiBase, QueryRequest req, long maxBodyBytes) {
        return exchange(() -> http.post().uri(apiBase + "/search").contentType(MediaType.APPLICATION_JSON).body(req), maxBodyBytes);
    }

    /**
     * Pages through the values of a genomic concept on Rust HPDS, with the URL Java HPDS receives.
     *
     * @param apiBase the Rust API base URL, the API path included
     * @param conceptPath the genomic concept whose values are listed
     * @param query text the values must contain
     * @param page the one-based page number, or null
     * @param size the page size, or null
     * @param maxBodyBytes the most body bytes kept, or {@link #UNLIMITED}
     * @return the answer or the failure
     */
    public RustOutcome searchConceptValues(
        String apiBase, String conceptPath, String query, Integer page, Integer size, long maxBodyBytes
    ) {
        return exchange(() -> {
            URI uri = UriComponentsBuilder.fromUriString(apiBase + "/search/values/").queryParam("genomicConceptPath", conceptPath)
                .queryParam("query", query).queryParamIfPresent("page", Optional.ofNullable(page))
                .queryParamIfPresent("size", Optional.ofNullable(size)).encode().build().toUri();
            return http.get().uri(uri);
        }, maxBodyBytes);
    }

    private RustOutcome exchange(RequestStarter starter, long maxBodyBytes) {
        try {
            return starter.start().exchange((request, response) -> {
                byte[] body = maxBodyBytes == UNLIMITED ? response.getBody().readAllBytes()
                    : response.getBody().readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBodyBytes + 1));
                boolean truncated = body.length > maxBodyBytes;
                return new RustOutcome.Answered(
                    response.getStatusCode().value(), truncated ? Arrays.copyOf(body, (int) maxBodyBytes) : body,
                    response.getHeaders().getFirst(ResourceWebClient.QUERY_METADATA_FIELD), truncated
                );
            });
        } catch (RestClientException e) {
            return new RustOutcome.Failed(classify(e));
        } catch (RuntimeException e) {
            return new RustOutcome.Failed(RustFailure.CLIENT_ERROR);
        }
    }

    /**
     * Classifies a transport failure by the first recognizable cause.
     *
     * @param e the client exception
     * @return {@link RustFailure#POOL_EXHAUSTED}, {@link RustFailure#CONNECT_ERROR}, {@link RustFailure#TIMEOUT},
     *         {@link RustFailure#IO_ERROR}, or {@link RustFailure#CLIENT_ERROR} for a client exception with no I/O cause, such as a body
     *         that could not be converted
     */
    static RustFailure classify(Throwable e) {
        boolean io = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectionRequestTimeoutException) {
                return RustFailure.POOL_EXHAUSTED;
            }
            if (
                cause instanceof ConnectException || cause instanceof ConnectTimeoutException || cause instanceof NoRouteToHostException
                    || cause instanceof UnknownHostException
            ) {
                return RustFailure.CONNECT_ERROR;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException) {
                return RustFailure.TIMEOUT;
            }
            io |= cause instanceof IOException;
        }
        return io ? RustFailure.IO_ERROR : RustFailure.CLIENT_ERROR;
    }

    /** Builds the request up to the point where it is sent. */
    @FunctionalInterface
    private interface RequestStarter {
        RestClient.RequestHeadersSpec<?> start();
    }
}
