package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SECURITY-CRITICAL: when PSAMA injected consent filters (ATTR_MUTATED_QUERY), the query it returned REPLACES the outbound body wholesale
 * before forwarding. Forwarding the un-swapped body would leak unauthorized data, so this swap must never be skipped when the attribute is
 * present.
 *
 * <p>Whole-body replacement, not a splice: the body on every query path is a bare v3 Query, i.e. exactly the node
 * {@code PsamaIntrospectionFilter} sent as the introspection {@code query} and exactly what PSAMA rewrote and sent back. There is no
 * envelope left to preserve a {@code "query"} key inside, so anything the original body carried alongside the query is precisely what must
 * NOT survive.
 *
 * <p>FAIL CLOSED, both ways: if a mutation is required and the replacement body cannot be built — or the stashed node is not a JSON object,
 * which would write out an unrunnable scalar body and silently discard the consent filtering — the request is rejected with a 500 rather
 * than forwarded with the original, un-swapped body.
 */
public class BodyMutationFilter extends OncePerRequestFilter {

    /** A {@code JsonNode} OBJECT: the consent-mutated v3 Query, stashed by {@code PsamaIntrospectionFilter}. */
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
        if (mutated instanceof JsonNode mutatedQuery && req instanceof BufferedRequestWrapper buffered) {
            if (!mutatedQuery.isObject()) {
                // Defence in depth: PsamaIntrospectionFilter already denies a non-object mutated query. Reaching
                // here means that guard was bypassed, and writing a scalar body would drop the consent filtering.
                log.error("Mutated query is a {}, not a JSON object; rejecting request", mutatedQuery.getNodeType());
                writeMutationFailed(resp);
                return;
            }
            try {
                buffered.setBody(json.writeValueAsBytes(mutatedQuery));
            } catch (IOException e) {
                // FAIL CLOSED: a mutation was required (consent filters were injected) but could not be applied.
                // Forwarding the original body here would leak unauthorized data, so reject instead of proceeding.
                log.error("Could not serialize mutated query; rejecting request instead of forwarding original body. {}", e.getMessage());
                writeMutationFailed(resp);
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    private static void writeMutationFailed(HttpServletResponse resp) throws IOException {
        GatewayErrors.write(
            resp, HttpStatus.INTERNAL_SERVER_ERROR, "body_mutation_failed",
            "Unable to apply required consent filtering to the request body."
        );
    }
}
