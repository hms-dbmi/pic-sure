package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class CountProcessor extends AbstractProcessor { 

	Logger log = Logger.getLogger(CountProcessor.class);

	public CountProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

	public CountProcessor(boolean isOnlyForTests) throws ClassNotFoundException, FileNotFoundException, IOException  {
		super(true);
		if(!isOnlyForTests) {
			throw new IllegalArgumentException("This constructor should never be used outside tests");
		}
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
	 * Returns a separate observation count for each field in query.crossCountFields when that field is added
	 * as a requiredFields entry for the base query.
	 * 
	 * @param query
	 * @return
	 */
	public Map<String, Integer> runObservationCrossCounts(Query query) {
		TreeMap<String, Integer> counts = new TreeMap<>();
		TreeSet<Integer> baseQueryPatientSet = getPatientSubsetForQuery(query);
		query.crossCountFields.parallelStream().forEach((String concept)->{
			try {
				//breaking these statements to allow += operator to cast long to int.
				int observationCount = 0;
				observationCount += Arrays.stream(getCube(concept).sortedByKey()).filter(keyAndValue->{
					return baseQueryPatientSet.contains(keyAndValue.getKey());
				}).collect(Collectors.counting());
				counts.put(concept, observationCount);
			} catch (Exception e) {
				counts.put(concept, -1);
			}
		});
		return counts;
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
			try {
				Query safeCopy = new Query();
				safeCopy.requiredFields = new ArrayList<String>();
				safeCopy.requiredFields.add(concept);
				counts.put(concept, Sets.intersection(getPatientSubsetForQuery(safeCopy), baseQueryPatientSet).size());
			} catch (Exception e) {
				counts.put(concept, -1);
			}
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

	/**
	 * Process only variantInfoFilters to count the number of variants that would be included in evaluating the query.
	 * 
	 * This does not actually evaluate a patient set for the query.
	 * 
	 * @param incomingQuery
	 * @return the number of variants that would be used to filter patients if the incomingQuery was run as a COUNT query.
	 */
	public Map<String, Object> runVariantCount(Query query) {
		TreeMap<String, Object> response = new TreeMap<String, Object>();
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			response.put("count", getVariantList(query).size());
			response.put("message", "Query ran successfully");
		} else {
			response.put("count", "0");
			response.put("message", "No variant filters were supplied, so no query was run.");
		}
		return response;
	}
}
