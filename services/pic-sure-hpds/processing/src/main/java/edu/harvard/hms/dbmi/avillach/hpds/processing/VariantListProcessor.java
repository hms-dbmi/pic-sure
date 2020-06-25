package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.log4j.Logger;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantMaskBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class VariantListProcessor extends AbstractProcessor {

	private VariantMetadataIndex metadataIndex = new VariantMetadataIndex();
	
	private static Logger log = Logger.getLogger(VariantListProcessor.class);
	
	public VariantListProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
	}
	
	public VariantListProcessor(boolean isOnlyForTests) throws ClassNotFoundException, FileNotFoundException, IOException  {
		super(true);
		if(!isOnlyForTests) {
			throw new IllegalArgumentException("This constructor should never be used outside tests");
		}
	}	

	@Override
	public void runQuery(Query query, AsyncResult asyncResult)
			throws NotEnoughMemoryException {
		throw new RuntimeException("Not yet implemented");
	}

	/**
	 * 
	 * The incomingQuery is a normal query, the same as COUNT result type.
	 * 
	 * This should not actually do any filtering based on bitmasks, just INFO columns.
	 * 
	 * @param incomingQuery
	 * @return a List of VariantSpec strings that would be eligible to filter patients if the incomingQuery was run as a COUNT query.
	 */
	public String runVariantListQuery(Query query) {
		return  Arrays.toString( getVariantList(query).toArray());
	}
	
	private ArrayList<String> getVariantList(Query query){
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			Set<String> unionOfInfoFilters = new TreeSet<>();
			for(VariantInfoFilter filter : query.variantInfoFilters){
				ArrayList<Set<String>> variantSets = new ArrayList<>();
				addVariantsMatchingFilters(filter, variantSets);
				Set<String> intersectionOfInfoFilters = null;

				if(!variantSets.isEmpty()) {
					for(Set<String> variantSet : variantSets) {
						if(intersectionOfInfoFilters == null) {
							intersectionOfInfoFilters = variantSet;
						} else {
							intersectionOfInfoFilters = Sets.intersection(intersectionOfInfoFilters, variantSet);
						}
					}
				}else {
					intersectionOfInfoFilters = new TreeSet<String>();
					log.error("No info filters included in query.");
				}
				unionOfInfoFilters.addAll(intersectionOfInfoFilters);
			}
			
			
			return new ArrayList<String>(unionOfInfoFilters);
			}		
		return new ArrayList<>();
			
		
	}
	

	/**
	 * To be implemented as part of ALS-115
	 * 
	 * Note that this is returning a plain old String. In the future, once the behavior
	 * has been approved for the prototype, this will be moved to runQuery and processing
	 * will asynchronously batch rows into a temp file just like the DATAFRAME queries. 
	 * 
	 * For now we hack it on purpose to get something in front of the users.
	 * 
	 * @param incomingQuery A query which contains only VariantSpec strings in the fields array.
	 * @return a String that is the entire response as a TSV encoded to mimic the VCF4.1 specification but with the necessary columns. This should include all variants in the request that at least 1 patient in the subset has.
	 */
	public String runVcfExcerptQuery(Query query) {
		
		ArrayList<String> variantList = getVariantList(query);
		
		log.info("INFO ARRAY:   "  + Arrays.toString(variantList.toArray()));
		
		Map<String, String[]> metadata = metadataIndex.findByMultipleVariantSpec(variantList);
		
		StringBuilder builder = new StringBuilder();
		
		TreeSet<Integer> patientSubset = getPatientSubsetForQuery(query);
		log.info(Arrays.toString(patientSubset.toArray()) + "   " + patientSubset.size());
		
		builder.append(patientSubset);
		
		
		builder.append("headers: " + Arrays.toString(variantStore.getVCFHeaders()));
		String[] patientIds = variantStore.getPatientIds();
		Arrays.sort(patientIds);
		log.info("Patient IDs: " + patientIds.length + "   ::   " +  Arrays.toString(patientIds));
		
		metadata.forEach((String column, String[] values)->{
			
			builder.append(column);
			builder.append("\t");
			builder.append(Arrays.toString(values));
			builder.append("\n");
			
			try {
				log.info("Masks");
				VariantMasks masks = variantStore.getMasks(column, new VariantMaskBucketHolder());
				
				String heteroMask = masks.heterozygousMask == null? null :masks.heterozygousMask.toString(2);
				String homoMask = masks.homozygousMask == null? null :masks.homozygousMask.toString(2);
				log.info("heteroMask size: " + heteroMask == null ? 0 : heteroMask.length() + "  data: " + heteroMask);
				log.info("homoMask   size: " + homoMask == null ? 0 : homoMask.length() + "  data: " + homoMask);
				
				
				log.info(masks.heterozygousNoCallMask);
				log.info(masks.homozygousNoCallMask);
				
				int idPointer = 0;
				for(Integer index : patientSubset) {
					
					
					int patientId = Integer.parseInt(patientIds[index]);
					
					log.info("Index " + index + "     ID " + patientId);
					
					while(idPointer < patientIds.length) {
						int key = Integer.parseInt(patientIds[idPointer]);
						if(key < index) {
							idPointer++;	
						} else if(key == index){
							if(heteroMask != null && '1' == heteroMask.charAt(idPointer + 2)) {
								log.info("Patient " + index + ":  " +" 0/1");
							}else if(homoMask != null && '1' == homoMask.charAt(idPointer + 2)) {
								log.info("Patient " + index + ":  " +" 1/1");
							}{
								log.info("Patient " + index + ":  " +" 0/0");
							}
							break;
						} else {
							log.info("ID POINTER SKIPPING " + idPointer + " Patient " + index + ":  " +"NO DATA");
							break;
						}
					}
				}
				
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			
		});
		
		log.info("SO FAR " + builder.toString());
		
		
		
		return builder.toString();
	}

}