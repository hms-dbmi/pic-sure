package edu.harvard.hms.dbmi.avillach.pheno;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import edu.harvard.hms.dbmi.avillach.pheno.data.AsyncResult;
import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;
import edu.harvard.hms.dbmi.avillach.pheno.data.Query;
import edu.harvard.hms.dbmi.avillach.pheno.processing.AbstractProcessor;

public class DataSubsetProcessor extends AbstractProcessor {
	
	public void runQuery(Query query, AsyncResult result) {
		// TODO : finish this
		throw new RuntimeException("Not yet implemented.");
//		ArrayList<Set<Integer>> filteredIdSets = idSetsForEachFilter(query);
//
//		TreeSet<Integer> idList;
//		if(filteredIdSets.isEmpty()) {
//			idList = allIds;
//		}else {
//			idList = new TreeSet<Integer>(applyBooleanLogic(filteredIdSets));
//		}
//		List<String> paths = query.fields;
//		int columnCount = paths.size() + 1;
//
//		ArrayList<Integer> columnIndex = useResidentCubesFirst(paths, columnCount);
//		
//
//		columnIndex.parallelStream().forEach((column)->{
//			addCubeToStore(column, getCube(query.fields.get(column-1)));
//		});

	}

	private void addCubeToStore(Integer column, PhenoCube cube) {
		
	}
	
}
