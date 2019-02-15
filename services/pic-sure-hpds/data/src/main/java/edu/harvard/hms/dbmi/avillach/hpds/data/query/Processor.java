package edu.harvard.hms.dbmi.avillach.hpds.data.query;

import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public interface Processor {

	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException;

}
