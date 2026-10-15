package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cleans the model's prose reply. The response's {@code query} is rebuilt from the validated proposal, so query JSON the model writes
 * into its reply (small models do, despite the prompt) is only noise.
 */
final class ReplyText {

    static final String PROPOSAL_ATTACHED_TEXT = "I've prepared an updated search for you to review.";

    private static final Pattern FENCED_BLOCK = Pattern.compile("```[a-zA-Z]*\\s*\\{.*?}\\s*```", Pattern.DOTALL);
    /** Quoted (JSON) or bare (JavaScript-object style, which small models also write). */
    private static final Pattern QUERY_KEYS = Pattern.compile(
        "\"(select|phenotypicClause|phenotypicClauses|phenotypicFilterType|genomicFilters)\""
            + "|\\b(?:phenotypicClauses?|phenotypicFilterType|genomicFilters)\\s*:|\\bselect\\s*:\\s*\\["
    );
    /** A line that is only a {@code query:}/{@code facets:}/{@code search:} label, optionally bulleted, with an empty or short value. */
    private static final Pattern FIELD_LABEL = Pattern.compile(
        "(?m)^[ \\t]*(?:[-*][ \\t]+)?(query|facets|search)[ \\t]*:[ \\t]*(?:\"[^\"\\n]*\"|\\[[^\\]\\n]*]|\\{\\s*}|undefined|null)?[ \\t]*$"
    );
    /** A heading or rule left at the end once the text under it was scrubbed away. */
    private static final Pattern DANGLING_HEADING = Pattern.compile("(?:^|\\n)[ \\t]*(?:-{3,}|\\*\\*[^*\\n]+\\*\\*:?|#{1,6}[ \\t]+[^\\n]*)[ \\t]*$");

    /** An empty {@code facets: []} / {@code search: ""} echoed inline. */
    private static final Pattern EMPTY_INLINE_FIELD = Pattern.compile("\\b(facets|search)[ \\t]*:[ \\t]*(\\[\\s*]|\"\")");
    /** Nothing but {@code query:}/{@code facets:}/{@code search:} labels with short values. */
    private static final Pattern LABELS_ONLY =
        Pattern.compile("(?s)\\s*(?:(?:query|facets|search)\\s*:\\s*(?:\"[^\"]*\"|\\[[^\\]]*]|\\{\\s*})?\\s*)+");

    /** Rejects trailing text, so prose after a JSON object is not taken for a bare proposal. */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROPOSAL_FIELDS = Set.of("query", "facets", "search");
    private static final Pattern WHOLE_FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$");

    /** U+2010 and U+2011, which gpt-oss writes in place of the ASCII hyphen. */
    private static final Pattern UNICODE_HYPHEN = Pattern.compile("[\u2010\u2011]");
    /** The JSON-escaped form of a concept path copied into prose. */
    private static final Pattern BACKSLASH_RUN = Pattern.compile("\\\\{2,}");

    /** A backticked span on one line containing a backslash: a concept path in code style. */
    private static final Pattern BACKTICKED_PATH = Pattern.compile("`([^`\\n]*\\\\[^`\\n]*)`");

    private static final Pattern SCREEN_CLAIM = Pattern.compile(
        "(?i)\\b(?:screen now|now (?:shown|displayed|showing|shows|displays)|(?:in|on) (?:your|the) (?:results )?(?:table|screen|view))\\b"
            + "|\\b(?:results table|table of results)\\b"
    );

    /**
     * "I included BMI in the query", "your query now has...". A change verb is required: a count answer says "participants in your
     * cohort" or "your cohort includes 1,200 participants" without claiming any change, and taking that for a claim would propose the
     * counted what-if query as the researcher's own.
     */
    private static final Pattern QUERY_CLAIM = Pattern.compile(
        "(?i)\\b(?:added|included|applied|updated|changed|built|created|narrowed|modified|replaced|removed)\\b.*\\b(?:query|cohort)\\b"
            + "|\\b(?:the|your) (?:query|cohort) (?:now|(?:has been|was) (?:updated|changed))\\b"
    );
    /** An offer rather than a claim: "let me know if you'd like to add filters to your query". */
    private static final Pattern OFFER = Pattern.compile("(?i)\\b(?:if you|would you|let me know|you can|you could|want|like to)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private ReplyText() {}

    static boolean claimsQueryChanged(String text) {
        if (text == null) {
            return false;
        }
        for (String sentence : SENTENCE_END.split(text)) {
            if (QUERY_CLAIM.matcher(sentence).find() && !OFFER.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean namesKnownPath(String text, ConceptPaths paths) {
        if (text == null) {
            return false;
        }
        Matcher matcher = BACKTICKED_PATH.matcher(text);
        while (matcher.find()) {
            if (paths.isKnown(matcher.group(1).strip())) {
                return true;
            }
        }
        return false;
    }

    static boolean claimsScreenChanged(String text) {
        return text != null && SCREEN_CLAIM.matcher(text).find();
    }

    /** No concept path contains a Unicode hyphen or a doubled backslash, so both are fixed everywhere in the reply. */
    static String withPlainPaths(String text) {
        if (text == null) {
            return null;
        }
        return BACKSLASH_RUN.matcher(UNICODE_HYPHEN.matcher(text).replaceAll("-")).replaceAll("\\\\");
    }

    /** The reply as the researcher sees it: no query JSON, and concept paths in the form a tool returned them. */
    static String cleaned(String text, ConceptPaths paths) {
        return withKnownPaths(withPlainPaths(withoutQueryJson(text)), paths);
    }

    /**
     * Puts each backticked path back in the exact form a tool returned, so the researcher can paste it. A path never seen is left as
     * written, since guessing would hide that it may be invented. Run {@link #withPlainPaths} first.
     */
    static String withKnownPaths(String text, ConceptPaths paths) {
        if (text == null) {
            return null;
        }
        return BACKTICKED_PATH.matcher(text).replaceAll(m -> {
            String path = m.group(1);
            String fixed = paths.isKnown(path) ? paths.canonical(path) : paths.snapped(path);
            return Matcher.quoteReplacement("`" + fixed + "`");
        });
    }

    /** Run {@link #withKnownPaths} first so shortened paths are already restored. */
    static List<String> unknownPaths(String text, ConceptPaths paths) {
        if (text == null) {
            return List.of();
        }
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = BACKTICKED_PATH.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1).strip();
            if (path.startsWith("\\") && !paths.isKnown(path)) {
                unknown.add(path);
            }
        }
        return List.copyOf(unknown);
    }

    /**
     * gpt-oss sometimes replies with nothing but its proposal: the {@code propose_query} arguments or a bare query, optionally fenced.
     *
     * @return the {@code propose_query} arguments as JSON, or null when the reply is anything else
     */
    static String proposalArguments(String text) {
        if (text == null) {
            return null;
        }
        String json = WHOLE_FENCE.matcher(text.strip()).replaceFirst("$1");
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (fields.stream().anyMatch(PROPOSAL_FIELDS::contains)) {
                return MAPPER.writeValueAsString(node);
            }
            if (fields.stream().anyMatch(ProposeQueryInput.FIELDS::contains)) {
                return MAPPER.writeValueAsString(MAPPER.createObjectNode().set("query", node));
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String withoutQueryJson(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = FENCED_BLOCK.matcher(text).replaceAll(m -> QUERY_KEYS.matcher(m.group()).find() ? "" : Matcher.quoteReplacement(m.group()));
        cleaned = removeQueryObjects(cleaned);
        cleaned = EMPTY_INLINE_FIELD.matcher(cleaned).replaceAll("");
        cleaned = FIELD_LABEL.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").strip();
        if (!cleaned.equals(text.strip())) {
            cleaned = withoutDanglingHeadings(cleaned);
        }
        return LABELS_ONLY.matcher(cleaned).matches() || cleaned.chars().noneMatch(Character::isLetterOrDigit) ? "" : cleaned;
    }

    private static String withoutDanglingHeadings(String text) {
        String current = text;
        while (true) {
            String next = DANGLING_HEADING.matcher(current).replaceFirst("").stripTrailing();
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
    }

    private static String removeQueryObjects(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '{') {
                int end = matchingBrace(text, i);
                if (end > 0 && QUERY_KEYS.matcher(text.substring(i, end + 1)).find()) {
                    i = end + 1;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** @return the index of the brace closing the one at {@code start}, or -1 when unbalanced */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
