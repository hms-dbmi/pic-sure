package edu.harvard.hms.dbmi.avillach.query.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compares a Java HPDS answer with a Rust HPDS answer to the same request, applying the normalization named for the result type. Where the
 * equivalence harness has a rule for the difference, the normalization carries that rule's name, so a logged name points at the rule that
 * explains it. Nothing here keeps, logs or digests either body.
 *
 * <p>{@code COUNT}, {@code VARIANT_COUNT_FOR_QUERY} and any type without a rule compare byte for byte ({@value #EXACT}). The four cross
 * counts compare as parsed JSON with object key order ignored ({@value #JSON_KEY_ORDER}); this name is the query service's own, because the
 * harness compares cross counts as parsed JSON without naming a rule. {@code VARIANT_LIST_FOR_QUERY} compares its variants as a multiset
 * ({@value #VARIANT_LIST_ORDER}), {@code INFO_COLUMN_LISTING} its entries as a multiset ({@value #INFO_COLUMN_LISTING_ORDER}), and
 * {@code VCF_EXCERPT} and {@code AGGREGATE_VCF_EXCERPT} their rows as a multiset, comma-joined cells as sets and columns as a map from
 * header to column ({@value #EXCERPT_ORDER}). {@code SEARCH} compares field for field, with every array, the concept list, category values
 * and info values included, as a multiset ({@value #SEARCH}). {@code SEARCH_VALUES} compares {@code page} and {@code total} exactly and the
 * {@code results} array as a multiset ({@value #SEARCH_VALUES_BUCKET_ORDER}).
 *
 * <p>Two non-2xx answers match when their statuses are equal ({@value #STATUS_ONLY}); a 2xx answer never matches a non-2xx one. A body over
 * {@link #MAX_COMPARED_BYTES} on either side, or a Rust body cut off at that limit, is not compared.
 */
public final class ShadowComparator {

    /** Byte-for-byte comparison, and the name of the rule for types that have no other. */
    public static final String EXACT = "exact";
    /** The name logged when the two bodies were identical before any normalization. */
    public static final String NONE = "none";
    /** Parsed JSON comparison, ignoring object key order; the query service's own name for the cross count comparison. */
    public static final String JSON_KEY_ORDER = "json-key-order";
    /** {@code VARIANT_LIST_FOR_QUERY} variants compared as a multiset. */
    public static final String VARIANT_LIST_ORDER = "variant-list-order";
    /** {@code INFO_COLUMN_LISTING} entries compared as a multiset. */
    public static final String INFO_COLUMN_LISTING_ORDER = "info-column-listing-order";
    /** Excerpt rows as a multiset, comma-joined cells as sets, and columns as a map from header to column. */
    public static final String EXCERPT_ORDER = "excerpt-order";
    /** Search with the concept list, category values and info values compared as multisets. */
    public static final String SEARCH = "search-concept-order,search-category-values-order,search-info-values-order";
    /** {@code /search/values} pages with page and total exact and the results as a set. */
    public static final String SEARCH_VALUES_BUCKET_ORDER = "search-values-bucket-order";
    /** Two non-2xx answers, compared by status only. */
    public static final String STATUS_ONLY = "status-only";
    /** The largest body compared, 16 MiB. */
    public static final int MAX_COMPARED_BYTES = 16 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> RULES = Map.ofEntries(
        Map.entry("CROSS_COUNT", JSON_KEY_ORDER), Map.entry("CATEGORICAL_CROSS_COUNT", JSON_KEY_ORDER),
        Map.entry("CONTINUOUS_CROSS_COUNT", JSON_KEY_ORDER), Map.entry("OBSERVATION_CROSS_COUNT", JSON_KEY_ORDER),
        Map.entry("SEARCH_VALUES", SEARCH_VALUES_BUCKET_ORDER), Map.entry("VARIANT_LIST_FOR_QUERY", VARIANT_LIST_ORDER),
        Map.entry("INFO_COLUMN_LISTING", INFO_COLUMN_LISTING_ORDER), Map.entry("VCF_EXCERPT", EXCERPT_ORDER),
        Map.entry("AGGREGATE_VCF_EXCERPT", EXCERPT_ORDER), Map.entry("SEARCH", SEARCH)
    );

    /** The result of one comparison. */
    public enum Result {
        /** The answers are equal, before or after the named normalization. */
        MATCH("match"),
        /** The answers differ after the named normalization. */
        MISMATCH("mismatch"),
        /** The answers were not compared; the verdict's reason says why. */
        NOT_COMPARED("not-compared");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        /** @return the name used in log lines and as the metric tag value */
        public String label() {
            return label;
        }
    }

    /**
     * The outcome of comparing two answers.
     *
     * @param result match, mismatch or not compared
     * @param normalization the rule name that decided it, {@value #NONE} when the bodies were identical, or null when not compared
     * @param reason why the answers were not compared, or null when they were
     */
    public record Verdict(Result result, String normalization, String reason) {
    }

    /** The reason logged when a body is over {@link #MAX_COMPARED_BYTES}. */
    public static final String BODY_TOO_LARGE = "body-over-16-mib";

    private ShadowComparator() {}

    /**
     * Returns the normalization rule name for a result type.
     *
     * @param type a result type name, {@code SEARCH} or {@code SEARCH_VALUES}
     * @return the rule name, {@value #EXACT} for a type with no other rule
     */
    public static String ruleFor(String type) {
        return RULES.getOrDefault(type, EXACT);
    }

    /**
     * Compares Java's answer with Rust's.
     *
     * @param type the result type name, {@code SEARCH} or {@code SEARCH_VALUES}
     * @param javaStatus Java's HTTP status, or 0 when Java gave no HTTP answer
     * @param javaBody Java's body, or null when it has none
     * @param rust Rust's outcome
     * @return the verdict
     */
    public static Verdict compare(String type, int javaStatus, byte[] javaBody, RustOutcome rust) {
        if (rust instanceof RustOutcome.Failed failed) {
            return new Verdict(Result.NOT_COMPARED, null, failed.reason().label());
        }
        RustOutcome.Answered answered = (RustOutcome.Answered) rust;
        boolean javaOk = javaStatus >= 200 && javaStatus < 300;
        if (!javaOk || !answered.successful()) {
            return new Verdict(javaStatus == answered.status() ? Result.MATCH : Result.MISMATCH, STATUS_ONLY, null);
        }
        byte[] java = javaBody == null ? new byte[0] : javaBody;
        byte[] rs = answered.body() == null ? new byte[0] : answered.body();
        if (answered.truncated() || java.length > MAX_COMPARED_BYTES || rs.length > MAX_COMPARED_BYTES) {
            return new Verdict(Result.NOT_COMPARED, null, BODY_TOO_LARGE);
        }
        if (Arrays.equals(java, rs)) {
            return new Verdict(Result.MATCH, NONE, null);
        }
        String rule = ruleFor(type);
        return new Verdict(equalUnder(rule, java, rs) ? Result.MATCH : Result.MISMATCH, rule, null);
    }

    /**
     * Whether two bodies are equal after a rule's normalization. A body the rule cannot parse is never equal to anything.
     *
     * @param rule a rule name from this class
     * @param java Java's body
     * @param rust Rust's body
     * @return whether the normalized forms are equal
     */
    static boolean equalUnder(String rule, byte[] java, byte[] rust) {
        try {
            return switch (rule) {
                case JSON_KEY_ORDER -> MAPPER.readTree(java).equals(MAPPER.readTree(rust));
                case VARIANT_LIST_ORDER -> variantList(text(java)).equals(variantList(text(rust)));
                case INFO_COLUMN_LISTING_ORDER, SEARCH -> canonical(MAPPER.readTree(java)).equals(canonical(MAPPER.readTree(rust)));
                case EXCERPT_ORDER -> excerpt(text(java)).equals(excerpt(text(rust)));
                case SEARCH_VALUES_BUCKET_ORDER -> valuesPage(MAPPER.readTree(java)).equals(valuesPage(MAPPER.readTree(rust)));
                default -> false;
            };
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The comparable form of a {@code /search/values} page: every member but {@code results} as written, and {@code results} as a count of
     * each value, so order is ignored and a repeated value still counts.
     */
    static Map<String, Object> valuesPage(JsonNode page) {
        Map<String, Object> form = new TreeMap<>();
        page.properties().forEach(e -> form.put(e.getKey(), e.getValue()));
        JsonNode results = page.path("results");
        if (results.isArray()) {
            Map<JsonNode, Integer> counts = new HashMap<>();
            results.forEach(value -> counts.merge(value, 1, Integer::sum));
            form.put("results", counts);
        }
        return form;
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The variants of a {@code VARIANT_LIST_FOR_QUERY} body, {@code [a, b]} as written by Java's {@code List.toString}, sorted. */
    static List<String> variantList(String body) {
        String trimmed = body.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split(", ")).map(String::strip).sorted().toList();
    }

    /** A JSON tree with object keys sorted and every array sorted by the canonical text of its elements. */
    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            items.forEach(out::add);
            return out;
        }
        return node;
    }

    /**
     * The canonical form of a tab-separated excerpt: columns reordered by header, each comma-joined cell as its sorted parts, and the rows
     * sorted. A row with a different number of cells than the header keeps its cells in their written order.
     */
    static List<String> excerpt(String body) {
        List<String> lines = body.lines().filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        String[] header = lines.get(0).split("\t", -1);
        int[] order =
            IntStream.range(0, header.length).boxed().sorted(Comparator.comparing(i -> header[i])).mapToInt(Integer::intValue).toArray();
        List<String> canonical = new ArrayList<>();
        canonical.add(String.join("\t", Arrays.stream(order).mapToObj(i -> header[i]).toList()));
        List<String> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            List<String> reordered = cells.length == header.length ? Arrays.stream(order).mapToObj(i -> cells[i]).toList() : List.of(cells);
            rows.add(String.join("\t", reordered.stream().map(ShadowComparator::cellAsSet).toList()));
        }
        rows.sort(null);
        canonical.addAll(rows);
        return canonical;
    }

    private static String cellAsSet(String cell) {
        if (!cell.contains(",")) {
            return cell;
        }
        return String.join(",", Arrays.stream(cell.split(",", -1)).distinct().sorted().toList());
    }
}
