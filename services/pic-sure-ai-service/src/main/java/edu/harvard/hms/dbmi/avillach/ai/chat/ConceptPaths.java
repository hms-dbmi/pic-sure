package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    /** A code range such as {@code (G00-G99)}, which models drop when copying a long path. */
    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

    /** Normalized path to the form it was first seen in, so output can carry the path exactly as a tool returned it. */
    private final Map<String, String> seen = new HashMap<>();

    /** Categorical value to the concept path it was listed under. */
    private final Map<String, String> valueOwners = new HashMap<>();

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
                    learnValues(value.asText(), node.path("values"));
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

    private void learnValues(String path, JsonNode values) {
        if (values.isArray()) {
            values.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    valueOwners.putIfAbsent(item.asText().strip(), path);
                }
            });
        }
    }

    /**
     * Diagnosis folders list their child codes as values, and gpt-oss turns such a value into a path of its own.
     *
     * @return the seen path whose values include the written path's last folder, or null
     */
    String ownerOfValueIn(String path) {
        List<String> segments = path == null || path.isBlank() ? List.of() : segments(path);
        return segments.isEmpty() ? null : valueOwners.get(segments.getLast());
    }

    boolean isAncestorOfSeen(String path) {
        String normalized = normalize(path);
        return normalized != null && !normalized.isBlank() && !isKnown(path)
            && seen.keySet().stream().anyMatch(known -> known.startsWith(normalized));
    }

    private void learn(String path) {
        String normalized = normalize(path);
        if (normalized != null && !normalized.isBlank()) {
            seen.putIfAbsent(normalized, path);
        }
    }

    /**
     * @param path a concept path in any variant
     * @return whether this turn has seen the path (in any variant)
     */
    boolean isKnown(String path) {
        String normalized = normalize(path);
        return normalized != null && seen.containsKey(normalized);
    }

    /**
     * @param path a concept path in any variant
     * @return the path as a tool result or the researcher's query first showed it, or the input unchanged if it hasn't been seen
     */
    String canonical(String path) {
        String normalized = normalize(path);
        return normalized == null ? path : seen.getOrDefault(normalized, path);
    }

    /**
     * gpt-oss drops a folder's {@code (G00-G99)} suffix or a middle folder when it copies a long path into prose.
     *
     * @return the one seen path {@code path} abbreviates, or {@code path} unchanged when none or several do
     */
    String snapped(String path) {
        if (path == null || path.isBlank() || isKnown(path)) {
            return path;
        }
        List<String> wanted = segments(path);
        if (wanted.size() < 2) {
            return path;
        }
        String match = null;
        for (Map.Entry<String, String> entry : seen.entrySet()) {
            if (abbreviates(wanted, segments(entry.getKey()))) {
                if (match != null) {
                    return path;
                }
                match = entry.getValue();
            }
        }
        return match == null ? path : match;
    }

    static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : normalize(path).split("\\\\")) {
            if (!segment.isBlank()) {
                segments.add(segment.strip());
            }
        }
        return segments;
    }

    /**
     * Whether {@code wanted} is {@code full} with some middle folders left out. A folder may lose its trailing parenthetical, but one the
     * model does write must match: a different unit or range is a different variable.
     */
    private static boolean abbreviates(List<String> wanted, List<String> full) {
        if (full.isEmpty() || wanted.size() > full.size() || !sameFolder(wanted.getFirst(), full.getFirst())
            || !sameFolder(wanted.getLast(), full.getLast())) {
            return false;
        }
        int next = 0;
        for (String folder : full) {
            if (next < wanted.size() && sameFolder(wanted.get(next), folder)) {
                next++;
            }
        }
        return next == wanted.size();
    }

    private static boolean sameFolder(String written, String real) {
        return written.equals(real) || written.equals(TRAILING_PARENTHETICAL.matcher(real).replaceFirst(""));
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
