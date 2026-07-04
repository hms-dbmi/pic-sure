package edu.harvard.hms.dbmi.avillach.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;

/**
 * Canonical (order-insensitive on object keys, order-sensitive on arrays) comparison of the {@code query} JSON objects carried in a
 * {@link ShadowRecord}, plus a helper to flag an accidental {@code resourceCredentials} leak into a logged query (both sides are expected
 * to have already stripped it before logging; if either side didn't, that is itself a divergence worth calling out distinctly from an
 * ordinary query mismatch).
 */
public final class JsonCanonical {

    private JsonCanonical() {}

    /** True if two JSON trees are structurally equal, ignoring object-key order (arrays remain order-sensitive). */
    public static boolean equalIgnoringOrder(JsonNode a, JsonNode b) {
        boolean aNull = a == null || a.isNull();
        boolean bNull = b == null || b.isNull();
        if (aNull || bNull) {
            return aNull && bNull;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if (!b.has(field) || !equalIgnoringOrder(a.get(field), b.get(field))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equalIgnoringOrder(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** True if {@code node} (at any depth, including inside arrays) contains a {@code resourceCredentials} key. */
    public static boolean containsResourceCredentials(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("resourceCredentials")) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsResourceCredentials(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsResourceCredentials(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
