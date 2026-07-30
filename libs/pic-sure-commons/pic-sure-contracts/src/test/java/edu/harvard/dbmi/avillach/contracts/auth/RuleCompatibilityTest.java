package edu.harvard.dbmi.avillach.contracts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * SECURITY: deployed FISMA access rules are JsonPath expressions stored in PSAMA's database and evaluated against the introspection
 * payload's {@code request} node (AuthorizationService#isAuthorized -> AccessRuleService#extractAndCheckRule ->
 * {@code JsonPath.parse(request).read(rule)}). Those rules are data, not code: they cannot be refactored alongside these records. If the
 * serialized shape of {@link TargetedRequest} drifts -- the space in {@code "Target Service"} lost to camelCase, the query nested one level
 * deeper, the query emitted as an escaped string instead of an object -- every rule silently stops matching and authorization decisions
 * change without a single compile error. <p> This test is the executable proof that the typed contract serializes to exactly what the
 * deployed rules already read. It evaluates the real com.jayway.jsonpath implementation (same 2.9.0 PSAMA resolves) against the serialized
 * payload. Treat a failure here as a production access-control regression, never as a test to be updated.
 */
class RuleCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The two rule families deployed today. */
    private static final String TARGET_SERVICE_RULE = "$.['Target Service']";

    private static final String QUERY_FIELD_RULE = "$.query.expectedResultType";

    @Test
    void shouldResolveDeployedRulesAgainstSerializedRequestNode() throws JsonProcessingException {
        IntrospectionRequest introspection = new IntrospectionRequest(
            "tok", new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\",\"select\":[]}"))
        );

        String requestJson = MAPPER.writeValueAsString(MAPPER.valueToTree(introspection).get("request"));

        assertEquals("/hpds/auth/v3/query", JsonPath.read(requestJson, TARGET_SERVICE_RULE));
        assertEquals("COUNT", JsonPath.read(requestJson, QUERY_FIELD_RULE));
    }

    /**
     * PSAMA also reads {@code ((Map) requestBody).get("Target Service")} directly, so the literal key -- space and capitalisation included
     * -- has to survive serialization verbatim. A Jackson naming strategy applied upstream would break this assertion first.
     */
    @Test
    void shouldSerializeTargetServiceKeyWithItsSpace() throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(new TargetedRequest("/hpds/auth/v3/query", null));

        assertTrue(json.contains("\"Target Service\""), "the space in the Target Service key was lost: " + json);
        assertFalse(json.contains("targetService"), "the Java field name leaked onto the wire: " + json);
    }

    /**
     * The gateway omits "query" entirely when there is no body to authorize (PsamaIntrospectionFilter#buildIntrospectionRequest). PSAMA
     * tolerates that: extractAndCheckRule catches PathNotFoundException and decides on the rule type. Emitting {@code "query": null}
     * instead would turn a PathNotFound into a null match and change those decisions, so absence must stay absence.
     */
    @Test
    void shouldOmitQueryEntirelyWhenAbsent() throws JsonProcessingException {
        String requestJson = MAPPER.writeValueAsString(new TargetedRequest("/picsure/proxy/dictionary/search", null));

        assertEquals("{\"Target Service\":\"/picsure/proxy/dictionary/search\"}", requestJson);
        assertEquals("/picsure/proxy/dictionary/search", JsonPath.read(requestJson, TARGET_SERVICE_RULE));
        assertThrows(PathNotFoundException.class, () -> JsonPath.read(requestJson, QUERY_FIELD_RULE));
    }

    /**
     * The target service is omitted rather than emitted as null for a harsher reason than the query is. PSAMA's
     * {@code AccessRuleService#evaluateNode} dereferences the matched value eagerly ({@code requestBodyValue.getClass()}), and JsonPath
     * returns null -- not PathNotFoundException -- for a key that is present with a null value. So {@code "Target Service": null} turns
     * every rule bound to it into an NPE, i.e. a 500 that the gateway reports as a 502, where an absent key is a clean decision.
     */
    @Test
    void shouldOmitTargetServiceEntirelyWhenAbsent() throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(new TargetedRequest(null, null));

        assertEquals("{}", json);
        assertThrows(PathNotFoundException.class, () -> JsonPath.read(json, TARGET_SERVICE_RULE));
    }

    /**
     * The query has to stay a JSON object. Serializing it as an escaped string makes {@code $.query.<field>} unresolvable while
     * {@code $.query} still matches, which is exactly the failure mode that silently widens or narrows access.
     */
    @Test
    void shouldSerializeQueryAsObjectNotString() throws JsonProcessingException {
        String requestJson =
            MAPPER.writeValueAsString(new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\"}")));

        assertTrue(requestJson.contains("\"query\":{"), "query was not serialized as an object: " + requestJson);
        assertTrue(JsonPath.parse(requestJson).read("$.query") instanceof Map, "query did not parse as a JSON object");
    }

    /**
     * The wrapper the gateway POSTs is exactly {@code {token, request}}; the rules are anchored at the request node, so an extra level of
     * nesting or a renamed wrapper key would leave every deployed rule unresolvable.
     */
    @Test
    void shouldWrapPayloadAsTokenAndRequest() throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(
            new IntrospectionRequest(
                "tok", new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\"}"))
            )
        );

        assertEquals("tok", JsonPath.read(json, "$.token"));
        assertEquals("/hpds/auth/v3/query", JsonPath.read(json, "$.request.['Target Service']"));
        assertEquals("COUNT", JsonPath.read(json, "$.request.query.expectedResultType"));
    }
}
