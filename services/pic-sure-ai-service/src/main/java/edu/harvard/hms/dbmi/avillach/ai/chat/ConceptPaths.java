package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The concept paths seen during one chat turn, used to refuse a tool call that names a path the model was never shown.
 *
 * <p>Small models invent paths, often by copying the placeholder paths in a tool's example arguments. A path counts as seen if a tool result
 * or the researcher's current query contained it; backslash variants of the same path (doubled, or missing the leading or trailing one) are
 * treated as the same path. One instance per chat turn; not thread-safe.
 */
final class ConceptPaths {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BACKSLASH_RUN = Pattern.compile("\\\\+");

    private final Set<String> seen = new HashSet<>();

    /**
     * Collapses every run of backslashes to one and ensures exactly one leading and one trailing backslash.
     *
     * @param path a concept path in any of the variants models write
     * @return the normalized path; null and blank input are returned unchanged
     */
    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String collapsed = BACKSLASH_RUN.matcher(path.strip()).replaceAll(Matcher.quoteReplacement("\\"));
        if (!collapsed.startsWith("\\")) {
            collapsed = "\\" + collapsed;
        }
        if (!collapsed.endsWith("\\")) {
            collapsed = collapsed + "\\";
        }
        return collapsed;
    }

    /**
     * Records every {@code conceptPath} value, and every entry of a {@code select} array, found anywhere in a JSON document. Content that
     * isn't JSON (an error message, say) is ignored.
     *
     * @param json a tool result or other JSON text
     */
    void learnFrom(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            learnFrom(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            // Not JSON, so no paths to learn from it.
        }
    }

    /**
     * Same as {@link #learnFrom(String)} for an already-parsed document, e.g. the researcher's current query on the request.
     *
     * @param node a parsed JSON document, or null
     */
    void learnFrom(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if ("conceptPath".equals(entry.getKey()) && value.isTextual()) {
                    learn(value.asText());
                } else if ("select".equals(entry.getKey()) && value.isArray()) {
                    value.forEach(item -> {
                        if (item.isTextual()) {
                            learn(item.asText());
                        }
                    });
                }
                learnFrom(value);
            });
        } else if (node.isArray()) {
            node.forEach(this::learnFrom);
        }
    }

    private void learn(String path) {
        String normalized = normalize(path);
        if (normalized != null && !normalized.isBlank()) {
            seen.add(normalized);
        }
    }

    /**
     * @param path a concept path in any variant
     * @return whether this turn has seen the path (in any variant)
     */
    boolean isKnown(String path) {
        String normalized = normalize(path);
        return normalized != null && seen.contains(normalized);
    }

    /**
     * @param argumentsJson the raw JSON arguments of a tool call, or null
     * @return whether the arguments are present but not parseable JSON (e.g. a path written with single backslashes), in which case
     *         {@link #firstUnseenIn(String)} can't check them and the caller must refuse the call rather than let it through
     */
    static boolean isMalformed(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        try {
            MAPPER.readTree(argumentsJson);
            return false;
        } catch (JsonProcessingException e) {
            return true;
        }
    }

    /**
     * Finds the first concept path in a tool call's arguments that no search result (or the researcher's own query) has shown this turn.
     * Looks at every {@code conceptPath} value and every {@code select} entry, at any depth. Blank paths are skipped (the tool reports those
     * itself).
     *
     * @param argumentsJson the raw JSON arguments of a tool call, or null
     * @return the first unseen path as written, or null if every path was seen or the arguments can't be read
     */
    String firstUnseenIn(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return firstUnseenIn(MAPPER.readTree(argumentsJson));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String firstUnseenIn(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                String unseen = null;
                if ("conceptPath".equals(entry.getKey()) && value.isTextual()) {
                    unseen = unseenOrNull(value.asText());
                } else if ("select".equals(entry.getKey()) && value.isArray()) {
                    for (JsonNode item : value) {
                        if (unseen == null && item.isTextual()) {
                            unseen = unseenOrNull(item.asText());
                        }
                    }
                }
                if (unseen == null) {
                    unseen = firstUnseenIn(value);
                }
                if (unseen != null) {
                    return unseen;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String unseen = firstUnseenIn(item);
                if (unseen != null) {
                    return unseen;
                }
            }
        }
        return null;
    }

    private String unseenOrNull(String path) {
        return path.isBlank() || isKnown(path) ? null : path;
    }
}
