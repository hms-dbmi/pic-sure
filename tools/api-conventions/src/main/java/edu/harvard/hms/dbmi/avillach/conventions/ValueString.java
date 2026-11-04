package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A parsed {@code @Value} string: the property keys it reads and what is wrong with its shape.
 *
 * <p>The parse follows Spring's order. Placeholders resolve first, so a <code>${</code> inside an expression,
 * even inside a quoted SpEL string, is still a placeholder. A placeholder ends at the brace that balances its
 * opening, counting every <code>{</code> inside it, and its key ends at the first top-level colon. Whatever
 * follows that colon is the default, which may hold further placeholders and expressions.
 *
 * @param keys every placeholder key, in the order they appear, including keys nested inside defaults and
 *     expressions
 * @param problems one sentence fragment per shape defect, empty when the string is well formed
 */
public record ValueString(List<String> keys, List<String> problems) {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._\\-\\[\\]]+");

    /**
     * @param value the raw annotation value
     * @return its keys and shape problems
     */
    public static ValueString parse(String value) {
        Parser parser = new Parser(value);
        if (value.isBlank()) {
            parser.problems.add("is blank");
        } else {
            parser.sequence(0, value.length());
        }
        return new ValueString(List.copyOf(parser.keys), List.copyOf(parser.problems));
    }

    /** @return whether the string has no shape problems */
    public boolean wellFormed() {
        return problems.isEmpty();
    }

    private static final class Parser {

        private final String text;
        private final List<String> keys = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        Parser(String text) {
            this.text = text;
        }

        void sequence(int from, int to) {
            int at = from;
            while (at < to) {
                if (text.startsWith("${", at)) {
                    at = placeholder(at, to);
                } else if (text.startsWith("#{", at)) {
                    at = expression(at, to);
                } else {
                    if (text.charAt(at) == '}') {
                        problems.add("has a closing brace at offset " + at + " that closes nothing");
                    }
                    at++;
                }
            }
        }

        int placeholder(int start, int limit) {
            int end = matchingClose(start + 2, limit);
            if (end < 0) {
                problems.add("opens a placeholder at offset " + start + " that is never closed");
                return limit;
            }
            int colon = topLevelColon(start + 2, end);
            String key = text.substring(start + 2, colon < 0 ? end : colon);
            if (key.isBlank()) {
                problems.add("has a placeholder at offset " + start + " with no key");
            } else if (key.contains("${") || key.contains("#{")) {
                problems.add("builds the key '" + key + "' from another placeholder or expression");
            } else if (!KEY.matcher(key).matches()) {
                problems.add("has the key '" + key + "', which uses characters other than letters, digits, '.', '-', '_' and '[]'");
            } else {
                keys.add(key);
            }
            if (colon >= 0) {
                sequence(colon + 1, end);
            }
            return end + 1;
        }

        int expression(int start, int limit) {
            int end = matchingClose(start + 2, limit);
            if (end < 0) {
                problems.add("opens an expression at offset " + start + " that is never closed");
                return limit;
            }
            if (text.substring(start + 2, end).isBlank()) {
                problems.add("has an empty expression at offset " + start);
            }
            int at = start + 2;
            while (at < end) {
                at = text.startsWith("${", at) ? placeholder(at, end) : at + 1;
            }
            return end + 1;
        }

        private int matchingClose(int from, int limit) {
            int depth = 0;
            for (int at = from; at < limit; at++) {
                char c = text.charAt(at);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (depth == 0) {
                        return at;
                    }
                    depth--;
                }
            }
            return -1;
        }

        private int topLevelColon(int from, int to) {
            int depth = 0;
            for (int at = from; at < to; at++) {
                char c = text.charAt(at);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == ':' && depth == 0) {
                    return at;
                }
            }
            return -1;
        }
    }
}
