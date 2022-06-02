package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class CountProcessor extends AbstractProcessor { 

	Logger log = LoggerFactory.getLogger(CountProcessor.class);

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
	 * Count processor always returns same headers
	 */
	@Override
	public String[] getHeaderRow(Query query) {
		return new String[] {"Patient ID", "Count"};
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
	 * Returns a separate count for each field in the requiredFields and categoryFilters query.
	 *
	 * @param query
	 * @return a map of categorical data and their counts
	 */
	public  Map<String, Map<String, Integer>> runCategoryCrossCounts(Query query) {
		Map<String, Map<String, Integer>> categoryCounts = new TreeMap<>();
		TreeSet<Integer> baseQueryPatientSet = getPatientSubsetForQuery(query);
		query.requiredFields.parallelStream().forEach(concept -> {
			Map<String, Integer> varCount = new TreeMap<>();;
			try {
				TreeMap<String, TreeSet<Integer>> categoryMap = getCube(concept).getCategoryMap();
				categoryMap.forEach((String category, TreeSet<Integer> patientSet)->{
					if (baseQueryPatientSet.containsAll(patientSet)) {
						varCount.put(category, patientSet.size());
					} else {
						for (Integer patient : patientSet) {
							if (baseQueryPatientSet.contains(patient)) {
								varCount.put(category, varCount.getOrDefault(category, 1) + 1);
							} else {
								varCount.put(category, varCount.getOrDefault(category, 1));
							}
						}
					}
				});
				categoryCounts.put(concept, varCount);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		query.categoryFilters.keySet().parallelStream().forEach((String concept)-> {
			Map<String, Integer> varCount;
			try {
				TreeMap<String, TreeSet<Integer>> categoryMap = getCube(concept).getCategoryMap();
				varCount = new TreeMap<>();
				categoryMap.forEach((String category, TreeSet<Integer> patientSet)->{
					if (Arrays.asList(query.categoryFilters.get(concept)).contains(category)) {
						varCount.put(category, Sets.intersection(patientSet, baseQueryPatientSet).size());
					}
				});
			categoryCounts.put(concept, varCount);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		return categoryCounts;
	}

	/**
	 * Returns a separate count for each range in numericFilters in query.
	 *
	 * @param query
	 * @return a map of numerical data and their counts
	 */
	public Map<String, Map<Double, Integer>> runContinuousCrossCounts(Query query) {
		TreeMap<String, Map<Double, Integer>> conceptMap = new TreeMap<>();
		TreeSet<Integer> baseQueryPatientSet = getPatientSubsetForQuery(query);
		query.numericFilters.forEach((String concept, Filter.DoubleFilter range)-> {
			KeyAndValue[] pairs = getCube(concept).getEntriesForValueRange(range.getMin(), range.getMax());
			Map<Double, Integer> countMap = new TreeMap<>();
			Arrays.stream(pairs).forEach(kv -> {
				if (baseQueryPatientSet.contains(kv.getKey())) {
					if (countMap.containsKey(kv.getValue())) {
						countMap.put((double)kv.getValue(), countMap.get(kv.getValue()) + 1);
					} else {
						countMap.put((double)kv.getValue(), 1);
					}
				}
			});
			conceptMap.put(concept, countMap);
		});
		return conceptMap;
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
	 * @param query
	 * @return the number of variants that would be used to filter patients if the incomingQuery was run as a COUNT query.
	 */
	public Map<String, Object> runVariantCount(Query query) {
		TreeMap<String, Object> response = new TreeMap<String, Object>();
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			try {
				response.put("count", getVariantList(query).size());
			} catch (IOException e) {
				e.printStackTrace();
				response.put("count", "0");
				response.put("message", "An unexpected error occurred while processing the query, please contact us to let us know using the Contact Us option in the Help menu.");
			}
			response.put("message", "Query ran successfully");
		} else {
			response.put("count", "0");
			response.put("message", "No variant filters were supplied, so no query was run.");
		}
		return response;
	}
}
