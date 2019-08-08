package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;

public class CountProcessor extends AbstractProcessor { 

	public CountProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	/**
	 * Retrieves a list of patient ids that are valid for the query result and returns the size of that list.
	 * 
	 * @param query
	 * @return
	 * @throws TooManyVariantsException
	 */
	public int runCounts(Query query) throws TooManyVariantsException {
		return getPatientSubsetForQuery(query).size();
	}

	/**
	 * Returns a separate count for each field in query.crossCountFields when that field is added
	 * as a requiredFields entry for the base query.
	 * 
	 * @param query
	 * @return
	 * @throws TooManyVariantsException
	 */
	public HashMap<String, Integer> runCrossCounts(Query query) throws TooManyVariantsException {
		HashMap<String, Integer> counts = new HashMap<>();
		TooManyVariantsException[] exceptions = new TooManyVariantsException[1];
		query.crossCountFields.parallelStream().forEach((String concept)->{
			Query safeCopy = new Query(query);
			safeCopy.requiredFields.add(concept);
			try {
				counts.put(concept, runCounts(safeCopy));
			} catch (TooManyVariantsException e) {
				exceptions[0] = e;
			}
		});
		if(exceptions[0]!=null) {
			throw exceptions[0];
		}
		return counts;
	}

	/**
	 * Until we have a count based query that takes longer than 30 seconds to run, we should discourage
	 * running them asynchronously in the backend as this results in unnecessary request-response cycles.
	 */
	@Override
	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException {
		throw new UnsupportedOperationException("Counts do not run asynchronously.");
	}

}
