
package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantMaskBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
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
	 *  This method takes a Query input (expected but not validated to be expected result type = VCF_EXCERPT) and
	 *  returns a tab separated string representing a table describing the variants described by the query and the 
	 *  associated zygosities of subjects identified by the query. it includes a header row describing each column.
	 *  
	 *  The output columns start with the variant description (chromosome, position reference allele, and subsitution),
	 *  continuing with a series of columns describing the Info columns associated with the variant data.  A count 
	 *  of how many subjects in the result set have/do not have comes next, followed by one column per patient.
	 *  
	 *  The default patientId header value can be overridden by passing the ID_CUBE_NAME environment variable to 
	 *  the java VM.
	 *  
	 *  @param Query A VCF_EXCERPT type query
	 *  @return A Tab-separated string with one line per variant and one column per patient (plus variant data columns)
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
		
		if(metadata.isEmpty()) {
			return "No Variants Found\n"; //UI uses newlines to show result count
		}
		
		PhenoCube<String> idCube = null;
		if(!ID_CUBE_NAME.contentEquals("NONE")) {
			try {
				log.info("Looking up ID cube " + ID_CUBE_NAME);
				idCube = (PhenoCube<String>) store.get(ID_CUBE_NAME);
			} catch (ExecutionException |  InvalidCacheLoadException e) {
				log.warn("Unable to identify ID_CUBE_NAME data, using patientId instead.  " + e.getLocalizedMessage());
			}
		}
		
		//
		//Build the header row
		//
		StringBuilder builder = new StringBuilder();
		
		//5 columns for gene info
		builder.append("CHROM\tPOSITION\tREF\tALT");
		
		//now add the variant metadata column headers
		for(String key : infoStores.keySet()) {
			builder.append("\t" + key);
		}
		
		//patient count columns
		builder.append("\tPatients with this variant in subset\tPatients Without this variant in subset");
		
		//then one column per patient.  We also need to identify the patient ID and
		// map it to the right index in the bit mask fields.
		TreeSet<Integer> patientSubset = getPatientSubsetForQuery(query);
		Map<String, Integer> patientIndexMap = new LinkedHashMap<String, Integer>(); //keep a map for quick index lookups
		int index = 2; //variant bitmasks are bookended with '11'
		
		for(String patientId : variantStore.getPatientIds()) {
			Integer idInt = Integer.parseInt(patientId);
			if(patientSubset.contains(idInt)){
				patientIndexMap.put(patientId, index);
				builder.append("\t" + (idCube == null ? patientId :  idCube.getValueForKey(idInt)));
			}
			index++;
			
			if(patientIndexMap.size() >= patientSubset.size()) {
				break;
			}
		}
		//End of headers
		builder.append("\n");
		
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

				//track the number of subjects without the variant; use a second builder to keep the column order
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
				//then dump out the data
				builder.append("\t"+ (patientIndexMap.size() - notPresent) + "\t" + notPresent);
				builder.append(patientListBuilder.toString());
			} catch (IOException e) {
				log.error(e);
			}
			
			builder.append("\n");
		});
		
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