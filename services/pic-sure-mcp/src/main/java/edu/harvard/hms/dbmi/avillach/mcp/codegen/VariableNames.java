package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hands out distinct variable names for one generated program. A name comes from a hint such as a concept path: its last segment,
 * lowercased, with every character outside {@code [a-z0-9_]} turned into an underscore. A name already taken, or reserved by the language
 * or by the program's own variables, gets a counter suffix ({@code sex}, {@code sex_2}, {@code sex_3}). The same sequence of hints always
 * gives the same names.
 */
final class VariableNames {

    private static final int MAX_BASE_LENGTH = 40;

    private final Set<String> taken;

    private final String fallback;

    /**
     * Creates a name pool.
     *
     * @param reserved names that are never handed out without a suffix
     * @param fallback the base used when a hint leaves nothing usable
     */
    VariableNames(Set<String> reserved, String fallback) {
        this.taken = new HashSet<>(reserved);
        this.fallback = fallback;
    }

    /**
     * Names a concept path after its last non-empty segment, split on backslashes.
     *
     * @param conceptPath the concept path
     * @return a new, unused name
     */
    String forConceptPath(String conceptPath) {
        String[] segments = conceptPath.split("\\\\");
        String last = "";
        for (String segment : segments) {
            if (!segment.isBlank()) {
                last = segment;
            }
        }
        return next(last);
    }

    /**
     * Hands out the next free name for a hint.
     *
     * @param hint the text to derive the name from
     * @return a new, unused name
     */
    String next(String hint) {
        String base = sanitize(hint);
        String name = base;
        for (int counter = 2; taken.contains(name); counter++) {
            name = base + "_" + counter;
        }
        taken.add(name);
        return name;
    }

    private String sanitize(String hint) {
        StringBuilder out = new StringBuilder();
        for (char c : hint.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (plain) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String base = out.length() > MAX_BASE_LENGTH ? out.substring(0, MAX_BASE_LENGTH) : out.toString();
        base = base.endsWith("_") ? base.substring(0, base.length() - 1) : base;
        if (base.isEmpty()) {
            return fallback;
        }
        return Character.isDigit(base.charAt(0)) ? fallback + "_" + base : base;
    }
}
