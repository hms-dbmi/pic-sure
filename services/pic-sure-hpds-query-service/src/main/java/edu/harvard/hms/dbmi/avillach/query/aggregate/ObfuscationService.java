package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements privacy-critical obfuscation for both v1 and v3 aggregate requests. Counts are never randomized: a value is either suppressed
 * behind a "&lt; threshold" display (because it is below the threshold) or reported exactly.
 *
 * <p>A threshold of zero (or less) deliberately disables obfuscation for the path it governs: values pass through exactly, nothing is
 * suppressed, and the continuous gate stops gating. This is a supported configuration for deployments whose data is open by design, so it
 * is an explicit branch rather than an accident of the comparisons -- do not "harden" it into a validation error.
 *
 * <p>An aggregate's accumulated variance is capped at {@code maxVariance}; see {@link #aggregate}.
 *
 * <p>Three thresholds apply, each configured separately. COUNT and CROSS_COUNT use {@code threshold}; CATEGORICAL_CROSS_COUNT and
 * CONTINUOUS_CROSS_COUNT are chart data and use {@code categoricalThreshold} and {@code continuousThreshold} respectively, so the buckets a
 * chart renders can be tuned without moving the count cutoff.
 *
 * <p>Variance is no longer a randomization band -- it is the accumulated uncertainty a value inherits from suppressed descendants. A leaf
 * carries variance {@code threshold - 1} when suppressed and {@code 0} when exact. A concept path that is a prefix of other paths in the
 * same CROSS_COUNT response is an aggregate of those children: it keeps its own backend count, rounded up to the next multiple of the
 * threshold so the exact total cannot be differenced against the children, and takes the sum of its children's variance. Unintended
 * divergence here is a privacy regression.
 */
@Service
public class ObfuscationService {

    private static final String STUDIES_CONSENTS_KEY = "\\_studies_consents\\";
    private static final char PATH_SEPARATOR = '\\';

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VisualizationFormatter visualizationFormatter;

    private final int threshold;
    private final int categoricalThreshold;
    private final int continuousThreshold;
    private final int maxVariance;

    public ObfuscationService(AggregateProperties props, VisualizationFormatter visualizationFormatter) {
        this.visualizationFormatter = visualizationFormatter;
        this.threshold = props.getObfuscation().getThreshold();
        this.categoricalThreshold = props.getObfuscation().getCategoricalThreshold();
        this.continuousThreshold = props.getObfuscation().getContinuousThreshold();
        this.maxVariance = props.getObfuscation().getMaxVariance();
    }

    public int getThreshold() {
        return threshold;
    }

    public int getCategoricalThreshold() {
        return categoricalThreshold;
    }

    public int getContinuousThreshold() {
        return continuousThreshold;
    }

    public int getMaxVariance() {
        return maxVariance;
    }

    /**
     * Core privacy floor: small (potentially identifiable) cohorts get hidden behind a "&lt; threshold" display, encoded as count 0 with
     * variance {@code threshold - 1} -- the true value lies somewhere in 0..threshold-1, so that is exactly how far the published 0 can be
     * from the truth.
     *
     * <p>Returns empty when the value is at or above the threshold, meaning the caller should report the count unchanged.
     */
    Optional<ObfuscatedCount> applyThresholdFloor(int actualCount) {
        if (obfuscationDisabled(threshold)) {
            return Optional.empty();
        }
        if (actualCount < threshold) {
            return Optional.of(new ObfuscatedCount(0, "< " + threshold, threshold - 1));
        }
        return Optional.empty();
    }

    /**
     * String overload for callers (COUNT / CROSS_COUNT) that hold the value as a JSON string. Logs and returns empty on parse failure so
     * the caller can fall through to its untouched-value path.
     */
    Optional<ObfuscatedCount> applyThresholdFloor(String actualCount) {
        try {
            return applyThresholdFloor(Integer.parseInt(actualCount));
        } catch (NumberFormatException nfe) {
            logger.warn("Count was not a number! {}", actualCount);
            return Optional.empty();
        }
    }

    /** COUNT case: floor if below threshold, else report unchanged; non-numeric passes through unchanged. */
    public String obfuscateCount(String entityString) {
        return applyThresholdFloor(entityString).map(ObfuscatedCount::display).orElse(entityString);
    }

    /**
     * CROSS_COUNT case: suppress each below-threshold entry, report the rest exactly, and roll aggregate paths up from their children so an
     * aggregate's display carries the uncertainty its suppressed descendants introduce.
     */
    public Map<String, String> processCrossCounts(String entityString) throws JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<>() {});
        return obfuscateCrossCounts(crossCounts);
    }

    private Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts) {
        if (crossCounts == null) {
            return null;
        }
        Map<String, List<String>> childrenByParent = indexDirectChildren(crossCounts.keySet());
        Map<String, Node> resolved = new HashMap<>();
        crossCounts.replaceAll((key, raw) -> {
            Node node = resolve(key, raw, crossCounts, childrenByParent, resolved);
            return node == null ? raw : node.display();
        });
        return crossCounts;
    }

    /**
     * Maps each concept path to the paths directly beneath it. A path is a child of the longest other path in the same response that is an
     * ancestor of it, per {@link #isAncestorPath}.
     */
    private Map<String, List<String>> indexDirectChildren(Iterable<String> keys) {
        List<String> all = new ArrayList<>();
        keys.forEach(all::add);
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (String child : all) {
            String parent = null;
            for (String candidate : all) {
                if (isAncestorPath(candidate, child) && (parent == null || candidate.length() > parent.length())) {
                    parent = candidate;
                }
            }
            if (parent != null) {
                childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
            }
        }
        return childrenByParent;
    }

    /**
     * Whether {@code candidate} is a strict ancestor of {@code child} in the concept tree. Being a string prefix is not enough: the child
     * has to continue at a segment boundary, so either the candidate already ends with the separator or the child's next character is one.
     *
     * <p>Concept paths do not follow one convention -- real study-consent paths appear both as {@code \_studies_consents\phs001194\} and as
     * {@code \_studies_consents\phs000007\HMB} -- so neither a bare prefix test nor a trailing-separator requirement is correct on its own.
     * A bare prefix would make {@code ...\HMB} the parent of {@code ...\HMB_2}; requiring a trailing separator would stop
     * {@code ...\phs001194} from parenting {@code ...\phs001194\HMB}.
     */
    private static boolean isAncestorPath(String candidate, String child) {
        if (candidate.length() >= child.length() || !child.startsWith(candidate)) {
            return false;
        }
        return candidate.charAt(candidate.length() - 1) == PATH_SEPARATOR || child.charAt(candidate.length()) == PATH_SEPARATOR;
    }

    /**
     * Resolves one concept path, recursing into its children first. Returns null for a leaf whose raw value is not a number, so the caller
     * can pass the original string through untouched.
     */
    private Node resolve(
        String key, String raw, Map<String, String> crossCounts, Map<String, List<String>> childrenByParent, Map<String, Node> resolved
    ) {
        Node cached = resolved.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> children = childrenByParent.get(key);
        Node node;
        if (children == null || children.isEmpty()) {
            node = leaf(raw);
        } else {
            int variance = 0;
            for (String child : children) {
                Node resolvedChild = resolve(child, crossCounts.get(child), crossCounts, childrenByParent, resolved);
                if (resolvedChild != null) {
                    variance += resolvedChild.variance();
                }
            }
            node = aggregate(raw, variance);
        }
        if (node != null) {
            resolved.put(key, node);
        }
        return node;
    }

    /** A leaf is suppressed below the threshold and exact at or above it. */
    private Node leaf(String raw) {
        Integer count = parse(raw);
        if (count == null) {
            return null;
        }
        return count < threshold ? suppressed() : new Node(0, Integer.toString(count));
    }

    /**
     * An aggregate keeps its own backend count -- the children are summed by HPDS, not by us. Below the threshold it is suppressed like any
     * other value. See {@link #roundUpToThreshold} for why the rounding granularity is a whole threshold.
     *
     * <p>Above the threshold the treatment depends on whether anything beneath it was hidden. Once any descendant is suppressed the count
     * is rounded up to the next multiple of the threshold, so the total cannot be differenced against the visible children to recover the
     * hidden one, and the summed child uncertainty becomes its band. When no descendant was suppressed there is nothing to defend, so the
     * count is reported exactly with no band -- rounding would publish a wrong number and a band would invent uncertainty that does not
     * exist.
     *
     * <p>That sum is capped at {@code maxVariance}. A concept with many small children -- above all {@code \_studies_consents\}, which is a
     * prefix of every consent path injected into every CROSS_COUNT query -- would otherwise accumulate a band wide enough to make its own
     * total useless. The cap is applied to what the node reports upward as well, so it bounds every ancestor too.
     */
    private Node aggregate(String raw, int variance) {
        Integer count = parse(raw);
        if (count == null) {
            return null;
        }
        if (count < threshold) {
            return suppressed();
        }
        int accumulated = Math.min(variance, maxVariance);
        if (accumulated == 0) {
            // Nothing beneath this node was suppressed, so there is no hidden value for a caller to recover by differencing the total
            // against the children. With nothing to defend, rounding would only publish a wrong number and a band would only invent
            // uncertainty that does not exist.
            return new Node(0, Integer.toString(count));
        }
        return new Node(accumulated, roundUpToThreshold(count) + " ±" + accumulated);
    }

    /**
     * A suppressed value contributes {@code threshold - 1} to its ancestors, which is exactly how far its hidden total can sit from the
     * published 0. That also already bounds whatever uncertainty its own children carry, since a subtree below the threshold is itself
     * below the threshold.
     */
    private Node suppressed() {
        return new Node(threshold - 1, "< " + threshold);
    }

    /**
     * A non-positive threshold disables obfuscation for whichever path it governs. Callers branch on this explicitly so the intent is
     * legible: {@code AGGREGATE_OBFUSCATION_THRESHOLD=0} is a way to turn the feature off, not a misconfiguration to reject.
     */
    private static boolean obfuscationDisabled(int threshold) {
        return threshold <= 0;
    }

    /**
     * Rounds up to the next multiple of the threshold, leaving exact multiples alone.
     *
     * <p>The step is a whole threshold rather than some fraction of it because this is what defends the suppression guarantee. A caller who
     * subtracts the visible children from the total learns the suppressed children's combined value; the rounding is what blurs that
     * difference, so its granularity has to be at least as coarse as the range suppression hides.
     */
    private int roundUpToThreshold(int count) {
        if (threshold <= 0) {
            return count;
        }
        return ((count + threshold - 1) / threshold) * threshold;
    }

    private Integer parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            logger.warn("Cross-count was not a number! {}", raw);
            return null;
        }
    }

    /** One resolved concept path: the uncertainty it contributes to its ancestors, and the string the response displays for it. */
    private record Node(int variance, String display) {
    }

    /**
     * Suppresses continuous results when the raw study-consents cross-count is below the threshold. Numeric strings are compared directly;
     * missing or unparseable counts fail closed and are suppressed.
     */
    public boolean shouldSuppressContinuousCrossCounts(Map<String, String> crossCounts) {
        if (obfuscationDisabled(threshold)) {
            return false;
        }
        String v = crossCounts == null ? null : crossCounts.get(STUDIES_CONSENTS_KEY);
        if (v == null) {
            return true;
        }
        if (v.contains("< " + this.threshold)) {
            return true;
        }
        try {
            return Integer.parseInt(v.trim()) < this.threshold;
        } catch (NumberFormatException nfe) {
            logger.warn("Study-consents cross-count was not a number ({}); suppressing continuous response", v);
            return true;
        }
    }

    /**
     * CATEGORICAL case: bucket via the formatter, then obfuscate. Null inputs short-circuit to null; a null cross-count means the
     * study-consents lookup failed, so the response is suppressed rather than returned unobfuscated.
     */
    public String processCategoricalCrossCounts(String categoricalEntityString, String crossCountEntityString)
        throws JsonProcessingException {
        if (categoricalEntityString == null || crossCountEntityString == null) {
            return null;
        }
        Map<String, Map<String, Object>> categorical = objectMapper.readValue(categoricalEntityString, new TypeReference<>() {});
        if (categorical == null) {
            return categoricalEntityString;
        }
        for (Map.Entry<String, Map<String, Object>> entry : categorical.entrySet()) {
            if (visualizationFormatter.skipKey(entry.getKey())) continue;
            categorical.put(entry.getKey(), visualizationFormatter.processResults(entry.getValue()));
        }
        return objectMapper.writeValueAsString(obfuscateCategoricalCrossCount(categorical));
    }

    /** CATEGORICAL_CROSS_COUNT chart buckets, obfuscated against the categorical threshold. */
    public Map<String, Map<String, ObfuscatedCount>> obfuscateCategoricalCrossCount(Map<String, Map<String, Object>> crossCount) {
        return obfuscateChartBuckets(crossCount, categoricalThreshold);
    }

    /** CONTINUOUS_CROSS_COUNT chart buckets, obfuscated against the continuous threshold. */
    public Map<String, Map<String, ObfuscatedCount>> obfuscateContinuousCrossCount(Map<String, Map<String, Object>> crossCount) {
        return obfuscateChartBuckets(crossCount, continuousThreshold);
    }

    /**
     * Chart buckets are keyed by category label or bin range rather than by concept path, so unlike CROSS_COUNT they have no hierarchy to
     * roll up -- each bucket is obfuscated on its own against the chart threshold for its result type.
     */
    private Map<String, Map<String, ObfuscatedCount>> obfuscateChartBuckets(
        Map<String, Map<String, Object>> crossCount, int bucketThreshold
    ) {
        Map<String, Map<String, ObfuscatedCount>> result = new LinkedHashMap<>();
        crossCount.forEach((key, value) -> {
            Map<String, ObfuscatedCount> obfuscated = new LinkedHashMap<>();
            value.forEach((innerKey, innerValue) -> obfuscated.put(innerKey, obfuscateBucket(toInt(innerValue), bucketThreshold)));
            result.put(key, obfuscated);
        });
        return result;
    }

    /**
     * One chart bucket: suppressed below the threshold, otherwise reported as the midpoint of the threshold-wide bucket it falls into, so
     * the published value sits in the middle of the range it could have come from rather than at an edge.
     *
     * <p>Variance is the true distance the value could be off by, which differs between the two branches. A midpoint is within half a
     * threshold of the truth, so it bands at {@code threshold / 2}. A suppressed value publishes count 0 while the truth can be as high as
     * {@code threshold - 1}, so it bands at a whole {@code threshold} -- half a threshold there would understate the uncertainty.
     */
    private ObfuscatedCount obfuscateBucket(int count, int bucketThreshold) {
        if (obfuscationDisabled(bucketThreshold)) {
            return ObfuscatedCount.ofInt(count);
        }
        if (count < bucketThreshold) {
            return new ObfuscatedCount(0, "< " + bucketThreshold, bucketThreshold);
        }
        int midpoint = bucketMidpoint(count, bucketThreshold);
        int band = bucketThreshold / 2;
        return new ObfuscatedCount(midpoint, midpoint + " ±" + band, band);
    }

    /** The midpoint of the threshold-wide bucket a count falls into: with a threshold of 10, 10..19 all report as 15. */
    private static int bucketMidpoint(int count, int threshold) {
        if (threshold <= 0) {
            return count;
        }
        return (count / threshold) * threshold + threshold / 2;
    }

    /** Narrows Jackson's Object-typed Integer, Long, Double, or numeric String counts to int. */
    static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cross-count value was neither Number nor numeric String: " + value);
    }
}
