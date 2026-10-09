package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

/**
 * The property that selects which phenotype store the service reads. {@value #JAVABIN}, the default, keeps the javabin store
 * ({@code columnMeta.javabin}, {@code allObservationsStore.javabin}) and every existing bean. {@value #LAYOUT} reads the Rust HPDS layout
 * ({@code pheno.index}, {@code pheno.blocks}) and answers the v3 sync count types from it; it is an experiment for benchmarking and serves
 * no exports.
 */
public final class StoreFormat {

    /** Property or environment variable naming the store format. */
    public static final String PROPERTY = "HPDS_STORE_FORMAT";

    /** The javabin store, the default. */
    public static final String JAVABIN = "javabin";

    /** The Rust HPDS layout store. */
    public static final String LAYOUT = "layout";

    private StoreFormat() {}
}
