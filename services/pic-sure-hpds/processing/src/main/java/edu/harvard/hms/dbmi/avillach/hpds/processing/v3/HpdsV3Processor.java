package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

public interface HpdsV3Processor {
    /**
     * This should return a String array of the columns that will be exported in a DATAFRAME or COUNT type query.  default is NULL.
     * @param query
     * @return
     */
    String[] getHeaderRow(Query query);

    void runQuery(Query query, AsyncResult asyncResult);
}
