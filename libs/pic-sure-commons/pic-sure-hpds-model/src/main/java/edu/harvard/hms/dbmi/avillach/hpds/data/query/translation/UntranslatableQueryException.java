package edu.harvard.hms.dbmi.avillach.hpds.data.query.translation;

/**
 * Thrown when a legacy (v1) query expresses semantics the v3 model cannot represent -- currently only the case of multiple non-empty
 * {@code variantInfoFilters} groups, which HPDS OR's together but v3's flat {@code List<GenomicFilter>} cannot express.
 */
public class UntranslatableQueryException extends Exception {

    public UntranslatableQueryException(String message) {
        super(message);
    }
}
