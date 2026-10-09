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
 * Keeps query JSON out of the prose reply. The researcher never sees queries, and the structured {@code query} field is rebuilt from the
 * validated proposal, so any query JSON the model writes into its reply is noise at best. Small models do it despite the prompt, so
 * the reply is scrubbed here rather than trusted.
 */
final class ReplyText {

    /** Shown when scrubbing leaves nothing and a proposal is attached. */
    static final String PROPOSAL_ATTACHED_TEXT = "I've prepared an updated search for you to review.";

    private static final Pattern FENCED_BLOCK = Pattern.compile("```[a-zA-Z]*\\s*\\{.*?}\\s*```", Pattern.DOTALL);
    /** A query key, quoted (JSON) or bare (JavaScript-object style, which small models also write: {@code {select:[...], phenotypicClause:{...}}}). */
    private static final Pattern QUERY_KEYS = Pattern.compile(
        "\"(select|phenotypicClause|phenotypicClauses|phenotypicFilterType|genomicFilters)\""
            + "|\\b(?:phenotypicClauses?|phenotypicFilterType|genomicFilters)\\s*:|\\bselect\\s*:\\s*\\["
    );
    /** A whole line that is only a {@code query:}/{@code facets:}/{@code search:} label, optionally bulleted, with an empty or short value. */
    private static final Pattern FIELD_LABEL = Pattern.compile(
        "(?m)^[ \\t]*(?:[-*][ \\t]+)?(query|facets|search)[ \\t]*:[ \\t]*(?:\"[^\"\\n]*\"|\\[[^\\]\\n]*]|\\{\\s*}|undefined|null)?[ \\t]*$"
    );
    /** A heading or rule left at the very end once the text under it was scrubbed away. */
    private static final Pattern DANGLING_HEADING = Pattern.compile("(?:^|\\n)[ \\t]*(?:-{3,}|\\*\\*[^*\\n]+\\*\\*:?|#{1,6}[ \\t]+[^\\n]*)[ \\t]*$");

    /** An empty {@code facets: []} / {@code search: ""} the model echoes inline, e.g. after its query object was removed. */
    private static final Pattern EMPTY_INLINE_FIELD = Pattern.compile("\\b(facets|search)[ \\t]*:[ \\t]*(\\[\\s*]|\"\")");
    /** A reply that is nothing but {@code query:}/{@code facets:}/{@code search:} labels with short values. */
    private static final Pattern LABELS_ONLY =
        Pattern.compile("(?s)\\s*(?:(?:query|facets|search)\\s*:\\s*(?:\"[^\"]*\"|\\[[^\\]]*]|\\{\\s*})?\\s*)+");

    /** Rejects trailing text, so a reply with prose after its JSON object is not taken for a bare proposal. */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROPOSAL_FIELDS = Set.of("query", "facets", "search");
    private static final Set<String> QUERY_FIELDS = Set.of("select", "phenotypicClause", "genomicFilters");
    private static final Pattern WHOLE_FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$");

    /** U+2010 HYPHEN and U+2011 NON-BREAKING HYPHEN, which gpt-oss writes in place of the ASCII hyphen. */
    private static final Pattern UNICODE_HYPHEN = Pattern.compile("[\u2010\u2011]");
    /** A run of backslashes: the JSON-escaped form of a concept path copied into prose. */
    private static final Pattern BACKSLASH_RUN = Pattern.compile("\\\\{2,}");

    /** A backticked span on one line whose text contains a backslash: a concept path the model wrote in code style. */
    private static final Pattern BACKTICKED_PATH = Pattern.compile("`([^`\\n]*\\\\[^`\\n]*)`");

    private static final Pattern SCREEN_CLAIM = Pattern.compile(
        "(?i)\\b(?:screen now|now (?:shown|displayed|showing|shows|displays)|(?:in|on) (?:your|the) (?:results )?(?:table|screen|view))\\b"
            + "|\\b(?:results table|table of results)\\b"
    );

    private ReplyText() {}

    /** A statement that the researcher's query or cohort was changed: "I included BMI in the query", "your query now has...". */
    private static final Pattern QUERY_CLAIM = Pattern.compile(
        "(?i)\\b(?:in(?:to)?|to) (?:the|your) (?:query|cohort)\\b|\\b(?:the|your) (?:query|cohort) (?:now|includes|has been|was)\\b"
            + "|\\b(?:updated|changed|built) (?:the|your) (?:query|cohort)\\b"
    );
    /** A sentence offering to do something rather than saying it was done ("let me know if you'd like to add filters to your query"). */
    private static final Pattern OFFER = Pattern.compile("(?i)\\b(?:if you|would you|let me know|you can|you could|want|like to)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    /** Whether a reply says, outside an offer, that the researcher's query was changed. */
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

    /** Whether a reply names, in backticks, a concept path this turn has seen. */
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

    /** Whether a reply tells the researcher their screen now shows the variables. */
    static boolean claimsScreenChanged(String text) {
        return text != null && SCREEN_CLAIM.matcher(text).find();
    }

    /**
     * Puts concept paths in the reply back in the form the dictionary uses, so a researcher can copy one. No concept path contains a
     * Unicode hyphen or two backslashes in a row, and both are invisible slips in prose, so they are replaced everywhere.
     *
     * @param text the reply, possibly null
     * @return the reply with ASCII hyphens and single backslashes; null stays null
     */
    static String withPlainPaths(String text) {
        if (text == null) {
            return null;
        }
        return BACKSLASH_RUN.matcher(UNICODE_HYPHEN.matcher(text).replaceAll("-")).replaceAll("\\\\");
    }

    /**
     * Restores the exact form of each backticked concept path this turn has seen. gpt-oss drops a path's trailing backslash in longer
     * lists (T14 in round 9), which leaves a path the researcher cannot paste; {@link ConceptPaths} already treats that as the same path,
     * so its canonical form is put back. A shortened path that abbreviates exactly one seen path is restored
     * ({@link ConceptPaths#snapped}); any other path the turn never saw is left as written, since it may be invented and guessing would
     * hide that.
     *
     * @param text the reply, possibly null; run {@link #withPlainPaths} first so doubled backslashes are already collapsed
     * @param paths the concept paths this turn has seen
     * @return the reply with known paths in their canonical form; null stays null
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

    /**
     * The backticked concept paths in a reply that this turn never saw, each once, in the order written.
     *
     * @param text the reply, possibly null; run {@link #withKnownPaths} first so shortened paths are already restored
     * @param paths the concept paths this turn has seen
     * @return the unseen paths as written; empty when there are none
     */
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
     * A reply that is nothing but a proposal: the {@code propose_query} arguments ({@code {"query":{...}}}) or a bare query
     * ({@code {"select":[...],"phenotypicClause":{...}}}), optionally fenced. gpt-oss sometimes writes its proposal this way instead of
     * calling the tool.
     *
     * @param text the model's reply, possibly null
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
            if (fields.stream().anyMatch(QUERY_FIELDS::contains)) {
                return MAPPER.writeValueAsString(MAPPER.createObjectNode().set("query", node));
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Removes fenced code blocks and bare JSON objects that look like a query, then any {@code query:}/{@code facets:}/{@code search:}
     * label left dangling.
     *
     * @param text the model's reply, possibly null
     * @return the reply without query JSON, trimmed; null stays null
     */
    static String withoutQueryJson(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = FENCED_BLOCK.matcher(text).replaceAll(m -> QUERY_KEYS.matcher(m.group()).find() ? "" : java.util.regex.Matcher.quoteReplacement(m.group()));
        cleaned = removeQueryObjects(cleaned);
        cleaned = EMPTY_INLINE_FIELD.matcher(cleaned).replaceAll("");
        cleaned = FIELD_LABEL.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").strip();
        if (!cleaned.equals(text.strip())) {
            cleaned = withoutDanglingHeadings(cleaned);
        }
        // Residue with no words in it (a lone brace, a bare label echo) is not a reply.
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

    /** Drops each balanced {...} span (string-aware) whose text mentions a query key. */
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
