
package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

import com.google.common.collect.Sets;

import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantMaskBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class VariantListProcessor extends AbstractProcessor {

	private VariantMetadataIndex metadataIndex = null;
	
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
	 * @throws ExecutionException 
	 */
	public String runVcfExcerptQuery(Query query) {
		
		log.info("Running VCF Extract query");
		try {
			initializeMetadataIndex();
		} catch (ClassNotFoundException | IOException e1) {
			log.error("could not initialize metadata index!", e1);
		}
		
		ArrayList<String> variantList = getVariantList(query);
		Map<String, String[]> metadata = metadataIndex.findByMultipleVariantSpec(variantList);
		
		TreeSet<Integer> patientSubset = getPatientSubsetForQuery(query);
		
		PhenoCube<String> idCube = null;
		if(ID_CUBE_NAME.contentEquals("NONE")) {
			try {
				idCube = (PhenoCube<String>) store.get(ID_CUBE_NAME);
			} catch (ExecutionException e) {
				e.printStackTrace();
			}
		}
		StringBuilder builder = new StringBuilder();
		
		//
		//Build the header row
		//
		
		//5 columns for gene info
		builder.append("CHROM\tPOSITION\tREF\tALT");
		
		//now add the variant metadata column headers
		for(String key : infoStores.keySet()) {
			builder.append("\t" + key);
		}
		
		//patient count columns
		builder.append("\tPatients with this variant in subset\tPatients Without this variant in subset");
		
		//then one column per patient
		String[] patientIds = variantStore.getPatientIds();
		Map<String, Integer> patientIndexMap = new LinkedHashMap<String, Integer>();
		int index = 2;
		for(String patientId : patientIds) {
			Integer idInt = Integer.parseInt(patientId);
			if(patientSubset.contains(idInt)){
				patientIndexMap.put(patientId, index);
				if(idCube != null) {
					builder.append("\t" + idCube.getValueForKey(idInt));
				} else {
					builder.append("\t" + patientId);
				}
			}
			index++;
			if(patientIndexMap.size() >= patientSubset.size()) {
				break;
			}
		}
		//End of headers
		builder.append("\n");
		log.debug("HEADER ROW\n" + builder.toString());
		
		//loop over the variants identified, and build an output row
		metadata.forEach((String column, String[] infoColumns)->{
			log.debug("variant info for " + column + " :: " + Arrays.toString(infoColumns));
			
			String[] variantDataColumns = column.split(",");
			//4 fixed columns in variant ID (CHROM POSITION REF ALT)
			for(int i = 0; i < 4; i++) {
				if(i > 0) {
					builder.append("\t");
				}
				if(i < variantDataColumns.length) {
					builder.append(variantDataColumns[i]);
				}
			}
			
			if(infoColumns.length > 0) {
				//I'm not sure why infoColumns is an array; the data is in a single semi-colon delimited string.
				// e.g.,   key1=value1;key2=value2;....
				String[] metaDataColumns = infoColumns[0].split(";");
				
				Map<String,String> variantColumnMap = new HashMap<String, String>();
				for(String key : metaDataColumns) {
					String[] keyValue = key.split("=");
					if(keyValue.length == 2) {
						variantColumnMap.put(keyValue[0], keyValue[1]);
					}
				}
				
				//need to make sure columns are pushed out in the right order; use same iterator as headers
				for(String key : infoStores.keySet()) {
					builder.append("\t" + variantColumnMap.get(key));
				}
			}
			
			//Now put the patient zygosities in the right columns
			try {
				VariantMasks masks = variantStore.getMasks(column, new VariantMaskBucketHolder());
				
				//make strings of 000100 so we can just check 'char at'
				String heteroMask = masks.heterozygousMask == null? null :masks.heterozygousMask.toString(2);
				String homoMask = masks.homozygousMask == null? null :masks.homozygousMask.toString(2);
				log.info("heteroMask size: " + (heteroMask == null ? 0 : heteroMask.length()));
				log.info("homoMask   size: " + (homoMask == null ? 0 : homoMask.length()));

				//not sure what these 'NoCall' masks are; just leaving this note and ignoring them (nc)
//				log.info(masks.heterozygousNoCallMask);
//				log.info(masks.homozygousNoCallMask);
				
				StringBuilder patientListBuilder = new StringBuilder();
				
				int notPresent = 0;
				for(Integer patientIndex : patientIndexMap.values()) {
					if(heteroMask != null && '1' == heteroMask.charAt(patientIndex)) {
						patientListBuilder.append("\t0/1");
					}else if(homoMask != null && '1' == homoMask.charAt(patientIndex)) {
						patientListBuilder.append("\t1/1");
					}else {
						patientListBuilder.append("\t0/0");
						notPresent++;
					}
				}
				
				builder.append("\t"+ (patientIndexMap.size() - notPresent) + "\t" + notPresent);
				builder.append(patientListBuilder.toString());
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			builder.append("\n");
		});
		
		log.info("OUTPUT:  \n" + builder.toString());
		
		return builder.toString();
	}

	private void initializeMetadataIndex() throws IOException, ClassNotFoundException{
		if(metadataIndex == null) {
			String metadataIndexPath = "/opt/local/hpds/all/VariantMetadata.javabin";
			if(new File(metadataIndexPath).exists()) {
				try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(
						new FileInputStream(metadataIndexPath)))){
					metadataIndex = (VariantMetadataIndex) in.readObject();
				}catch(Exception e) {
					e.printStackTrace();
				}
				metadataIndex.initializeRead();			
			} 
		}
	}

}