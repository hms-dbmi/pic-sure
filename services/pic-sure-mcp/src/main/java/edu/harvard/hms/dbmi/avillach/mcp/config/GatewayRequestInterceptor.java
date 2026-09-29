package edu.harvard.hms.dbmi.avillach.mcp.config;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Runs on every request the gateway client sends. It refuses a request whose scheme, host, or port differs from the configured gateway, so
 * no call can reach a service directly, adds the MCP service credential as {@value #MCP_TOKEN_HEADER}, and logs one line per call with the
 * method, path, status, and duration but no header.
 */
public class GatewayRequestInterceptor implements ClientHttpRequestInterceptor {

    /** Header carrying the credential that proves to the gateway a call came from this service. */
    public static final String MCP_TOKEN_HEADER = "X-PIC-SURE-MCP-TOKEN";

    private static final Logger log = LoggerFactory.getLogger(GatewayRequestInterceptor.class);

    private final URI gateway;

    private final String serviceToken;

    /**
     * Creates the interceptor for one gateway.
     *
     * @param gatewayUrl the gateway base URL; every request must target its scheme, host, and port
     * @param serviceToken the MCP service credential
     * @throws IllegalArgumentException if the gateway URL has no host or the token is blank
     */
    public GatewayRequestInterceptor(String gatewayUrl, String serviceToken) {
        this.gateway = URI.create(gatewayUrl);
        if (gateway.getHost() == null) {
            throw new IllegalArgumentException("picsure.mcp.gateway-url must be an absolute URL with a host");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("picsure.mcp.service-token must not be blank");
        }
        this.serviceToken = serviceToken;
    }

    /**
     * Checks the target, adds the credential, sends the request, and logs the outcome.
     *
     * @param request the outbound request
     * @param body the request body
     * @param execution the rest of the chain
     * @return the gateway's response
     * @throws IOException if the request fails
     * @throws IllegalStateException if the request targets anything other than the configured gateway
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        URI target = request.getURI();
        if (!targetsGateway(target)) {
            throw new IllegalStateException(
                "Refusing to send a request to " + target.getHost() + ": outbound calls go only to the gateway"
            );
        }
        request.getHeaders().set(MCP_TOKEN_HEADER, serviceToken);
        long start = System.nanoTime();
        ClientHttpResponse response = execution.execute(request, body);
        log.info(
            "Gateway call {} {} returned {} in {} ms", request.getMethod(), target.getPath(), response.getStatusCode().value(),
            Duration.ofNanos(System.nanoTime() - start).toMillis()
        );
        return response;
    }

    private boolean targetsGateway(URI target) {
        return gateway.getScheme().equalsIgnoreCase(Objects.requireNonNullElse(target.getScheme(), ""))
            && gateway.getHost().equalsIgnoreCase(Objects.requireNonNullElse(target.getHost(), "")) && port(gateway) == port(target);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
