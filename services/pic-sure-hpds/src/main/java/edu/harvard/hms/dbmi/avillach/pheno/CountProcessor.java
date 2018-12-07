package edu.harvard.hms.dbmi.avillach.pheno;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import edu.harvard.hms.dbmi.avillach.pheno.data.AsyncResult;
import edu.harvard.hms.dbmi.avillach.pheno.data.Query;
import edu.harvard.hms.dbmi.avillach.pheno.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.pheno.store.NotEnoughMemoryException;

public class CountProcessor extends AbstractProcessor { 
	
	public int runCounts(Query query) {
		return countForQuery(query);
	}

	public HashMap<String, Integer> runCrossCounts(Query query) {
		HashMap<String, Integer> counts = new HashMap<>();
		query.crossCountFields.parallelStream().forEach((String concept)->{
			Query safeCopy = new Query(query);
			safeCopy.requiredFields.add(concept);
			counts.put(concept, runCounts(safeCopy));
		});
		return counts;
	}
	
	private int countForQuery(Query query) {
		ArrayList<Set<Integer>> filteredIdSets = idSetsForEachFilter(query);
		TreeSet<Integer> idList;
		if(filteredIdSets.isEmpty()) {
			idList = allIds; 
		}else {
			idList = new TreeSet<Integer>(applyBooleanLogic(filteredIdSets));
		}
		return idList.size();
	}

	@Override
	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException {
		throw new UnsupportedOperationException("Counts do not run asynchronously.");
	}
	
}
