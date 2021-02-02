
package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantMaskBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import java.io.FileNotFoundException;
import java.io.IOException;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class VariantListProcessor extends AbstractProcessor {

	private VariantMetadataIndex metadataIndex = null;

	private static Logger log = Logger.getLogger(VariantListProcessor.class);
	
	private static final Boolean VCF_EXCERPT_ENABLED;
	
	static {
		VCF_EXCERPT_ENABLED = "TRUE".equalsIgnoreCase(System.getProperty("VCF_EXCERPT_ENABLED", "FALSE"));
	}	

	public VariantListProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		super();
		initializeMetadataIndex();
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
		throw new RuntimeException("Not implemented");
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
	
	/**
	 * Process only variantInfoFilters to count the number of variants that would be included in evaluating the query.
	 * 
	 * @param incomingQuery
	 * @return the number of variants that would be used to filter patients if the incomingQuery was run as a COUNT query.
	 */
	public int runVariantCount(Query query) {
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			return getVariantList(query).size();
		}
		return 0;
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
		
		if(!VCF_EXCERPT_ENABLED) {
			log.warn("VCF_EXCERPT query attempted, but not enabled.");
			return "VCF_EXCERPT query type not allowed";
		}
		
		
		log.info("Running VCF Extract query");

		Collection<String> variantList = getVariantList(query);

		Map<String, String[]> metadata = (metadataIndex == null ? null : metadataIndex.findByMultipleVariantSpec(variantList));

		// Sort the variantSpecs so that the user doesn't lose their mind
		TreeMap<String, String[]> metadataSorted = new TreeMap<>((o1, o2) -> {
			return new VariantSpec(o1).compareTo(new VariantSpec(o2));
		});
		metadataSorted.putAll(metadata);
		metadata = metadataSorted;

		if(metadata == null || metadata.isEmpty()) {
			return "No Variants Found\n"; //UI uses newlines to show result count
		}

		PhenoCube<String> idCube = null;
		if(!ID_CUBE_NAME.contentEquals("NONE")) {
			try {
				//				log.info("Looking up ID cube " + ID_CUBE_NAME);
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
		builder.append("\tPatients with this variant in subset\tPatients With this variant NOT in subset");

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
		VariantMaskBucketHolder variantMaskBucketHolder = new VariantMaskBucketHolder();

		//loop over the variants identified, and build an output row
		metadata.forEach((String variantSpec, String[] variantMetadata)->{

			String[] variantDataColumns = variantSpec.split(",");
			//4 fixed columns in variant ID (CHROM POSITION REF ALT)
			for(int i = 0; i < 4; i++) {
				if(i > 0) {
					builder.append("\t");
				}
				if(i < variantDataColumns.length) {
					builder.append(variantDataColumns[i]);
				}
			}
			Map<String,Set<String>> variantColumnMap = new HashMap<String, Set<String>>();
			for(String infoColumns : variantMetadata) {
				//data is in a single semi-colon delimited string.
				// e.g.,   key1=value1;key2=value2;....

				String[] metaDataColumns = infoColumns.split(";");

				for(String key : metaDataColumns) {
					String[] keyValue = key.split("=");
					if(keyValue.length == 2 && keyValue[1] != null) {
						Set<String> existingValues = variantColumnMap.get(keyValue[0]);
						if(existingValues == null) {
							existingValues = new HashSet<String>();
							variantColumnMap.put(keyValue[0], existingValues); 
						}
						existingValues.add(keyValue[1]);
					}
				}
			}

			//need to make sure columns are pushed out in the right order; use same iterator as headers
			for(String key : infoStores.keySet()) {
				Set<String> columnMeta = variantColumnMap.get(key);
				if(columnMeta != null) {
					//collect our sets to a single entry
					builder.append("\t" +  columnMeta.stream().map( o ->{ return o.toString(); }).collect( Collectors.joining(",") ));
				} else {
					builder.append("\tnull");
				}
			}

			//Now put the patient zygosities in the right columns
			try {
				VariantMasks masks = variantStore.getMasks(variantSpec, variantMaskBucketHolder);

				//make strings of 000100 so we can just check 'char at'
				//so heterozygous no calls we want, homozygous no calls we don't
				String heteroMask = masks.heterozygousMask != null? masks.heterozygousMask.toString(2) : masks.heterozygousNoCallMask != null ? masks.heterozygousNoCallMask.toString(2) : null;
				String homoMask = masks.homozygousMask != null? masks.homozygousMask.toString(2) : null;

				//track the number of subjects without the variant; use a second builder to keep the column order
				StringBuilder patientListBuilder = new StringBuilder();
				int patientCount = 0;

				for(Integer patientIndex : patientIndexMap.values()) {
					if(heteroMask != null && '1' == heteroMask.charAt(patientIndex)) {
						patientListBuilder.append("\t0/1");
						patientCount++;
					}else if(homoMask != null && '1' == homoMask.charAt(patientIndex)) {
						patientListBuilder.append("\t1/1");
						patientCount++;
					}else {
						patientListBuilder.append("\t0/0");
					}
				}

				int bitCount = masks.heterozygousMask == null? 0 : (masks.heterozygousMask.bitCount() - 4);
				bitCount += masks.homozygousMask == null? 0 : (masks.homozygousMask.bitCount() - 4);

				Integer patientsWithVariantsCount = null;
				if(heteroMask != null) {
					patientsWithVariantsCount = heteroMask.length() - 4;
				} else if (homoMask != null ) {
					patientsWithVariantsCount = homoMask.length() - 4;
				} else {
					patientsWithVariantsCount = -1;
				}


				// (patients with/total) in subset   \t   (patients with/total) out of subset.
				builder.append("\t"+ patientCount + "/" + patientIndexMap.size() + "\t" + (bitCount - patientCount) + "/" + (patientsWithVariantsCount - patientIndexMap.size()));
				//then dump out the data
				builder.append(patientListBuilder.toString());
			} catch (IOException e) {
				log.error(e);
			}

			builder.append("\n");
		});

		StringBuilder b2 = new StringBuilder();
		for( String key : variantMaskBucketHolder.lastSetOfVariants.keySet()) {
			b2.append(key + "\t");
		}
		log.info("Found variants " + b2.toString());
		return builder.toString();
	}

	private void initializeMetadataIndex() throws IOException{
		if(metadataIndex == null) {
			String metadataIndexPath = "/opt/local/hpds/all/VariantMetadata.javabin";
			try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(
					new FileInputStream(metadataIndexPath)))){
				metadataIndex = (VariantMetadataIndex) in.readObject();
				metadataIndex.initializeRead();	
			}catch(Exception e) {
				log.error("No Metadata Index found at " + metadataIndexPath);
			}
		}
	}

}