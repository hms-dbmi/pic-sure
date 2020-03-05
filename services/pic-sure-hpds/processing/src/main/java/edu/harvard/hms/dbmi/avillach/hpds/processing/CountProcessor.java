package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class CountProcessor extends AbstractProcessor { 

	public CountProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	/**
	 * Retrieves a list of patient ids that are valid for the query result and returns the size of that list.
	 * 
	 * @param query
	 * @return
	 */
	public int runCounts(Query query) {
		return getPatientSubsetForQuery(query).size();
	}

	/**
	 * Retrieves a list of patient ids that are valid for the query result and total number
	 * of observations recorded for all concepts included in the fields array for those patients.
	 * 
	 * @param query
	 * @return
	 */
	public int runObservationCount(Query query) {
		TreeSet<Integer> patients = getPatientSubsetForQuery(query);
		int[] observationCount = {0};
		query.fields.stream().forEach(field -> {
			observationCount[0] += Arrays.stream(getCube(field).sortedByKey()).filter(keyAndValue->{
				return patients.contains(keyAndValue.getKey());
			}).collect(Collectors.counting());
		});
		return observationCount[0];
	}

	/**
	 * Returns a separate count for each field in query.crossCountFields when that field is added
	 * as a requiredFields entry for the base query.
	 * 
	 * @param query
	 * @return
	 */
	public Map<String, Integer> runCrossCounts(Query query) {
		TreeMap<String, Integer> counts = new TreeMap<>();
		TreeSet<Integer> baseQueryPatientSet = getPatientSubsetForQuery(query);
		query.crossCountFields.parallelStream().forEach((String concept)->{
			Query safeCopy = new Query();
			safeCopy.requiredFields = new ArrayList<String>();
			safeCopy.requiredFields.add(concept);
			counts.put(concept, Sets.intersection(getPatientSubsetForQuery(safeCopy), baseQueryPatientSet).size());
		});
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
