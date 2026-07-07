package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

public interface HpdsV3Processor {

    String[] getHeaderRow(Query query);

    void runQuery(Query query, AsyncResult asyncResult);
}
