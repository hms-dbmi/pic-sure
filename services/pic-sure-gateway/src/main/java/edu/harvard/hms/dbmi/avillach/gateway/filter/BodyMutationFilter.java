package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SECURITY-CRITICAL: when PSAMA injected consent filters (ATTR_MUTATED_QUERY), rewrite the buffered body's top-level "query" before
 * forwarding (mirrors the WAR's JWTFilter:304-308). Best-effort on a non-JSON body. Forwarding the un-swapped body would leak unauthorized
 * data, so this swap must never be skipped when the attribute is present. FAIL CLOSED: if a mutation is required (ATTR_MUTATED_QUERY
 * present) and building the mutated body throws, the request is rejected with a 500 rather than forwarded with the original, un-swapped
 * body.
 */
public class BodyMutationFilter extends OncePerRequestFilter {

    public static final String ATTR_MUTATED_QUERY = "mutatedQuery";
    private static final Logger log = LoggerFactory.getLogger(BodyMutationFilter.class);

    private final ObjectMapper json;

    public BodyMutationFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        Object mutated = req.getAttribute(ATTR_MUTATED_QUERY);
        if (mutated instanceof String mutatedQueryJson && req instanceof BufferedRequestWrapper buffered) {
            try {
                buffered.setBody(mergeQueryIntoBody(buffered.getBody(), mutatedQueryJson));
            } catch (IOException e) {
                // FAIL CLOSED: a mutation was required (consent filters were injected) but could not be applied.
                // Forwarding the original body here would leak unauthorized data, so reject instead of proceeding.
                log.error("Could not merge mutated query; rejecting request instead of forwarding original body. {}", e.getMessage());
                GatewayErrors.write(
                    resp, HttpStatus.INTERNAL_SERVER_ERROR, "body_mutation_failed",
                    "Unable to apply required consent filtering to the request body."
                );
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    private byte[] mergeQueryIntoBody(byte[] originalBody, String mutatedQueryJson) throws IOException {
        ObjectNode root;
        if (originalBody != null && originalBody.length > 0) {
            // Best-effort: a non-JSON original body must not abort the swap (that would forward the
            // un-swapped, potentially unauthorized body) -- fall back to a fresh object wrapping the query.
            JsonNode parsed;
            try {
                parsed = json.readTree(originalBody);
            } catch (IOException notJson) {
                parsed = null;
            }
            root = (parsed != null && parsed.isObject()) ? (ObjectNode) parsed : json.createObjectNode();
        } else {
            root = json.createObjectNode();
        }
        JsonNode mutatedNode;
        try {
            mutatedNode = json.readTree(mutatedQueryJson);
        } catch (IOException notJson) {
            mutatedNode = json.getNodeFactory().textNode(mutatedQueryJson);
        }
        root.set("query", mutatedNode);
        return json.writeValueAsBytes(root);
    }
}
