package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;

public class VariantsOfInterestProcessor extends AbstractProcessor {

	public VariantsOfInterestProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}

//	public Map<String, Double> runVariantsOfInterestQuery(Query query) throws ExecutionException {
//		List<String[]> geneNameFilters = query.variantInfoFilters.stream()
//				.filter((VariantInfoFilter filter)->{return filter.categoryVariantInfoFilters.get("GN")!=null;})
//				.map((filter)->{return filter.categoryVariantInfoFilters.get("GN");}).collect(Collectors.toList());
//		String geneName = geneNameFilters.get(0)[0];
//		List<Set<Integer>> idSets;
//		try {
//			idSets = idSetsForEachFilter(query);
//			Set<Integer> ids = new TreeSet<Integer>();
//			ids.addAll(idSets.get(0));
//			for(int x = 1;x<idSets.size();x++) {
//				ids = Sets.intersection(ids, idSets.get(x));
//			}
//			String subsetMaskString = "11";
//			
//			// get id concept
//			PhenoCube<String> idCube = (PhenoCube<String>) store.get(ID_CUBE_NAME);
//			
//			String[] patientIds = variantStore.getPatientIds();
//			// for each patientId in variantStore, if the id is in ids, add a 1, else add a 0
//			for(int x = 0;x < patientIds.length;x++) {
//				int patientPhenoId = idCube.getKeysForValue(patientIds[x].split("_")[0]).iterator().next();
//				if(ids.contains(patientPhenoId)) {
//					subsetMaskString += 1;
//				}else {
//					subsetMaskString += 0;
//				}
//			}
//
//			BigInteger subsetMask = new BigInteger(subsetMaskString, 2);
//
//			try {
//				return super.variantsOfInterestForSubset(geneName, subsetMask, .05);
//			} catch (IOException e) {
//				e.printStackTrace();
//				throw new RuntimeException(e);
//			}
//		} catch (TooManyVariantsException e1) {
//			// TODO Auto-generated catch block
//			e1.printStackTrace();
//			throw new RuntimeException(e1);
//		}
//	}

	@Override
	public void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException {
		throw new UnsupportedOperationException("Variants of interest do not run asynchronously.");
	}
}
