package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/**
 * The result the generated code asks for. The constants are lowercase because they are the values the model sends, and they match the
 * adapters' own result type names.
 */
public enum ResultKind {

    /** One exact participant count. */
    count(ResultType.COUNT),

    /** One exact count per concept path. */
    cross_count(ResultType.CROSS_COUNT),

    /** One row per matching participant. */
    participant(ResultType.DATAFRAME),

    /** Participant-level timestamps for longitudinal concepts. */
    timestamp(ResultType.DATAFRAME_TIMESERIES);

    private final ResultType resultType;

    ResultKind(ResultType resultType) {
        this.resultType = resultType;
    }

    /**
     * The v3 result type the adapter sends for this result.
     *
     * @return the result type
     */
    public ResultType resultType() {
        return resultType;
    }
}
