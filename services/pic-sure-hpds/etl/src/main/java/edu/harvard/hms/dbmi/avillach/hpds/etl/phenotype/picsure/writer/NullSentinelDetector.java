package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import java.util.Set;

/**
 * Utility for detecting null sentinel values in string data.
 *
 * <p>Null sentinels are string representations of missing/null data that should be treated as absent observations rather than legitimate
 * values. This prevents numeric concepts from being incorrectly promoted to categorical when encountering string representations of NULL
 * like "None", "nan", "null", etc.</p>
 *
 * <p>Examples of null sentinels:</p> <ul> <li>"" (empty string)</li> <li>"nan", "NaN" (pandas NaN string)</li> <li>"na", "NA", "N/A"
 * (R-style or human-readable)</li> <li>"null", "NULL" (JSON null string)</li> <li>"none", "None", "NONE" (Python None string)</li>
 * <li>"\\N" (MySQL null marker)</li> </ul>
 *
 * <p>Matching is case-insensitive and whitespace is trimmed before comparison.</p>
 *
 * <p><strong>Alignment with Python ETL:</strong> The null sentinel list matches the null_tokens of the parquet-metadata-rewriter used by
 * the curation pipelines, to keep the whole data pipeline consistent.</p>
 */
public class NullSentinelDetector {

    /**
     * Default null sentinel tokens (case-insensitive matching).
     *
     * These match the null_tokens of the parquet-metadata-rewriter config: <pre> null_tokens: - "" # empty string - "nan" # pandas NaN
     * string - "na" # R-style NA - "n/a" # human-readable not applicable - "null" # JSON null string - "none" # Python None string - "\\N"
     * # MySQL null marker - "NaN", "NULL", "NONE" # case-variants (handled by toLowerCase) </pre>
     */
    private static final Set<String> DEFAULT_NULL_SENTINELS = Set.of(
        "", // empty string
        "nan", // pandas NaN string
        "na", // R-style NA
        "n/a", // human-readable not applicable
        "null", // JSON null string
        "none", // Python None string
        "\\n" // MySQL null marker (normalized from "\\N")
    );

    private final Set<String> nullSentinels;

    /**
     * Create detector with default null sentinels.
     */
    public NullSentinelDetector() {
        this.nullSentinels = DEFAULT_NULL_SENTINELS;
    }

    /**
     * Create detector with custom null sentinels.
     *
     * <p>Custom sentinels will be normalized (trimmed and lowercased) during comparison, so the input set should contain lowercase
     * strings.</p>
     *
     * @param customSentinels Set of null sentinel strings (case-insensitive)
     */
    public NullSentinelDetector(Set<String> customSentinels) {
        this.nullSentinels = customSentinels != null ? customSentinels : DEFAULT_NULL_SENTINELS;
    }

    /**
     * Check if a value is a null sentinel.
     *
     * <p>A value is considered a null sentinel if:</p> <ul> <li>The value is null</li> <li>The value is a String that matches
     * (case-insensitive, trimmed) one of the configured null sentinels</li> </ul>
     *
     * <p>Non-string values (e.g., Double, Integer) always return false.</p>
     *
     * @param value Value to check (can be any type)
     * @return true if value is a recognized null sentinel, false otherwise
     */
    public boolean isNullSentinel(Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof String) {
            String str = ((String) value).trim().toLowerCase();
            return nullSentinels.contains(str);
        }

        // Non-string values are never null sentinels
        return false;
    }

    /**
     * Get the set of configured null sentinels.
     *
     * @return Unmodifiable set of null sentinel strings (lowercase)
     */
    public Set<String> getNullSentinels() {
        return Set.copyOf(nullSentinels);
    }
}
