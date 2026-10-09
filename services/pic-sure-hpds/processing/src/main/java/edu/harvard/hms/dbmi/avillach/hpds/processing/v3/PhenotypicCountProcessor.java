package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.Map;

/**
 * The v3 sync count result types, which {@code PicSureV3Service} serializes to JSON (or, for COUNT, plain text) unchanged. Implemented by
 * {@link CountV3Processor} over the javabin store and by the layout store's processor.
 */
public interface PhenotypicCountProcessor {

    /**
     * @param query the query
     * @return the number of patients matching it
     */
    int runCounts(Query query);

    /**
     * @param query the query
     * @return for each {@code select} path, the number of matching patients with a value for it
     */
    Map<String, Integer> runCrossCounts(Query query);

    /**
     * @param query the query
     * @return for each categorical concept the query filters on, the number of matching patients per category
     */
    Map<String, Map<String, Integer>> runCategoryCrossCounts(Query query);

    /**
     * @param query the query
     * @return for each numeric concept the query filters on, the number of matching observations per value
     */
    Map<String, Map<Double, Integer>> runContinuousCrossCounts(Query query);

    /**
     * @param query the query
     * @return for each known {@code select} path, the number of its observations belonging to matching patients
     */
    Map<String, Integer> runObservationCrossCounts(Query query);
}
