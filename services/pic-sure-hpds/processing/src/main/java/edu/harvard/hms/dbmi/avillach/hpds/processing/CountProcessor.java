package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;

public class CountProcessor extends AbstractProcessor { 

	public CountProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	public int runCounts(Query query) throws TooManyVariantsException {
		return countForQuery(query);
	}

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

	private int countForQuery(Query query) throws TooManyVariantsException{
		return getPatientSubsetForQuery(query).size();
	}

	@Override
	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException {
		throw new UnsupportedOperationException("Counts do not run asynchronously.");
	}

}
