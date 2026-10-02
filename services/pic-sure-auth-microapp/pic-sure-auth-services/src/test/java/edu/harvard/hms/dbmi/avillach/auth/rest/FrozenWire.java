package edu.harvard.hms.dbmi.avillach.auth.rest;

import java.util.List;

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.auth.config.ApplicationConfig;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;

/**
 * Builds the JSON an admin endpoint has to keep emitting, from the entity the endpoint returned before it returned a response record. The
 * expected body is the entity as this service's own {@link ObjectMapper} writes it, with the members the contract drops removed and nothing
 * else changed. A test that compares a response to that string, character for character, proves the record kept every other member, its
 * order, and the rule for leaving out null and empty ones.
 *
 * <p>{@code mergedValues} and {@code mergedName} are removed from every access rule at any depth, because an access rule is nested in
 * privileges, roles, users and applications. The members named by the caller are removed only from the root object, or from each element
 * when the root is an array.</p>
 */
final class FrozenWire {

    static final ObjectMapper MAPPER = new ApplicationConfig(null).objectMapper();

    private FrozenWire() {}

    /**
     * Builds a MockMvc around one controller that writes bodies with this service's {@link ObjectMapper} and maps exceptions with its
     * {@link GlobalExceptionHandler}, without a Spring context.
     *
     * @param controller the controller under test
     * @return the MockMvc instance
     */
    static MockMvc mockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    /**
     * Serializes an entity, or a list of entities, the way the endpoint did before, then removes the dropped members.
     *
     * @param entityOrEntities one entity or a list of entities
     * @param droppedRootMembers members to remove from the root object, or from each element of a root array
     * @return the JSON text the endpoint must return
     */
    static String json(Object entityOrEntities, String... droppedRootMembers) {
        JsonNode tree = MAPPER.valueToTree(entityOrEntities);
        for (JsonNode accessRule : tree.findParents("mergedValues")) {
            ((ObjectNode) accessRule).remove(List.of("mergedValues", "mergedName"));
        }
        List<String> dropped = List.of(droppedRootMembers);
        if (tree.isArray()) {
            tree.forEach(element -> ((ObjectNode) element).remove(dropped));
        } else if (tree.isObject()) {
            ((ObjectNode) tree).remove(dropped);
        }
        return write(tree);
    }

    /**
     * Wraps already serialized content in this service's {@code {message, content}} envelope.
     *
     * @param message the envelope's message
     * @param contentJson the JSON text of the content member
     * @return the JSON text of the envelope
     */
    static String envelope(String message, String contentJson) {
        return "{\"message\":" + write(message) + ",\"content\":" + contentJson + "}";
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
