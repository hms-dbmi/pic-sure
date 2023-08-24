
package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VariantListProcessor implements HpdsProcessor {

	private final VariantMetadataIndex metadataIndex;

	private static Logger log = LoggerFactory.getLogger(VariantListProcessor.class);
	
	private final Boolean VCF_EXCERPT_ENABLED;
	private final Boolean AGGREGATE_VCF_EXCERPT_ENABLED;
	private final Boolean VARIANT_LIST_ENABLED;
	private final String ID_CUBE_NAME;
	private final int ID_BATCH_SIZE;
	private final int CACHE_SIZE;

	private final AbstractProcessor abstractProcessor;


	@Autowired
	public VariantListProcessor(AbstractProcessor abstractProcessor) {
		this.abstractProcessor = abstractProcessor;
		this.metadataIndex = VariantMetadataIndex.createInstance(VariantMetadataIndex.VARIANT_METADATA_BIN_FILE);

		VCF_EXCERPT_ENABLED = "TRUE".equalsIgnoreCase(System.getProperty("VCF_EXCERPT_ENABLED", "FALSE"));
		//always enable aggregate queries if full queries are permitted.
		AGGREGATE_VCF_EXCERPT_ENABLED = VCF_EXCERPT_ENABLED || "TRUE".equalsIgnoreCase(System.getProperty("AGGREGATE_VCF_EXCERPT_ENABLED", "FALSE"));
		VARIANT_LIST_ENABLED = VCF_EXCERPT_ENABLED || AGGREGATE_VCF_EXCERPT_ENABLED;
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

	}

	public VariantListProcessor(boolean isOnlyForTests, AbstractProcessor abstractProcessor)  {
		this.abstractProcessor = abstractProcessor;
		this.metadataIndex = null;

		VCF_EXCERPT_ENABLED = "TRUE".equalsIgnoreCase(System.getProperty("VCF_EXCERPT_ENABLED", "FALSE"));
		//always enable aggregate queries if full queries are permitted.
		AGGREGATE_VCF_EXCERPT_ENABLED = VCF_EXCERPT_ENABLED || "TRUE".equalsIgnoreCase(System.getProperty("AGGREGATE_VCF_EXCERPT_ENABLED", "FALSE"));
		VARIANT_LIST_ENABLED = VCF_EXCERPT_ENABLED || AGGREGATE_VCF_EXCERPT_ENABLED;
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE", "100"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE", "0"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME", "NONE");

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
	 * @throws IOException 
	 */
	public String runVariantListQuery(Query query) throws IOException {
		
		if(!VARIANT_LIST_ENABLED) {
			log.warn("VARIANT_LIST query attempted, but not enabled.");
			return "VARIANT_LIST query type not allowed";
		}
		
		return  Arrays.toString( abstractProcessor.getVariantList(query).toArray());
	}
	
	/**
	 * Process only variantInfoFilters to count the number of variants that would be included in evaluating the query.
	 * 
	 * @param incomingQuery
	 * @return the number of variants that would be used to filter patients if the incomingQuery was run as a COUNT query.
	 * @throws IOException 
	 */
	public int runVariantCount(Query query) throws IOException {
		if(!query.getVariantInfoFilters().isEmpty()) {
			return abstractProcessor.getVariantList(query).size();
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
	 *  @param includePatientData whether to include patient specific data
	 *  @return A Tab-separated string with one line per variant and one column per patient (plus variant data columns)
	 * @throws IOException 
	 */
	public String runVcfExcerptQuery(Query query, boolean includePatientData) throws IOException {
		
		if(includePatientData && !VCF_EXCERPT_ENABLED) {
			log.warn("VCF_EXCERPT query attempted, but not enabled.");
			return "VCF_EXCERPT query type not allowed";
		} else if (!includePatientData && !AGGREGATE_VCF_EXCERPT_ENABLED) {
			log.warn("AGGREGATE_VCF_EXCERPT query attempted, but not enabled.");
			return "AGGREGATE_VCF_EXCERPT query type not allowed";
		}
		
		
		log.info("Running VCF Extract query");

		Collection<String> variantList = abstractProcessor.getVariantList(query);
		
		log.debug("variantList Size " + variantList.size());

		Map<String, String[]> metadata = (metadataIndex == null ? null : metadataIndex.findByMultipleVariantSpec(variantList));

		log.debug("metadata size " + metadata.size());
		
		// Sort the variantSpecs so that the user doesn't lose their mind
		TreeMap<String, String[]> metadataSorted = new TreeMap<>((o1, o2) -> {
			return new VariantSpec(o1).compareTo(new VariantSpec(o2));
		});
		metadataSorted.putAll(metadata);
		metadata = metadataSorted;

		if(metadata == null || metadata.isEmpty()) {
			return "No Variants Found\n"; //UI uses newlines to show result count
		} else {
			log.debug("Found " + metadata.size() + " varaints");
		}

		PhenoCube<String> idCube = null;
		if(!ID_CUBE_NAME.contentEquals("NONE")) {
			idCube = (PhenoCube<String>) abstractProcessor.getCube(ID_CUBE_NAME);
		}

		//
		//Build the header row
		//
		StringBuilder builder = new StringBuilder();

		//5 columns for gene info
		builder.append("CHROM\tPOSITION\tREF\tALT");

		//now add the variant metadata column headers
		for(String key : abstractProcessor.getInfoStoreColumns()) {
			builder.append("\t" + key);
		}

		//patient count columns
		builder.append("\tPatients with this variant in subset\tPatients with this variant NOT in subset");

		//then one column per patient.  We also need to identify the patient ID and
		// map it to the right index in the bit mask fields.
		TreeSet<Integer> patientSubset = abstractProcessor.getPatientSubsetForQuery(query);
		log.debug("identified " + patientSubset.size() + " patients from query");
		Map<String, Integer> patientIndexMap = new LinkedHashMap<String, Integer>(); //keep a map for quick index lookups
		BigInteger patientMasks = abstractProcessor.createMaskForPatientSet(patientSubset);
		int index = 2; //variant bitmasks are bookended with '11'

		
		for(String patientId : abstractProcessor.getPatientIds()) {
			Integer idInt = Integer.parseInt(patientId);
			if(patientSubset.contains(idInt)){
				patientIndexMap.put(patientId, index);
				if(includePatientData) {
					if(idCube==null) {
						builder.append("\t" + patientId);
					} else {
						String value = idCube.getValueForKey(idInt);
						if(value==null) {
							builder.append("\t" + patientId);
						}else {
							builder.append("\t" + idCube.getValueForKey(idInt));
						}
					}
				}
			}
			index++;

			if(patientIndexMap.size() >= patientSubset.size()) {
				log.info("Found all " + patientIndexMap.size() + " patient Indices at index " + index);
				break;
			}
		}
		//End of headers
		builder.append("\n");
		VariantBucketHolder<VariantMasks> variantMaskBucketHolder = new VariantBucketHolder<VariantMasks>();

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
			for(String key : abstractProcessor.getInfoStoreColumns()) {
				Set<String> columnMeta = variantColumnMap.get(key);
				if(columnMeta != null) {
					//collect our sets to a single entry
					builder.append("\t" +  columnMeta.stream().map(String::toString).collect( Collectors.joining(",") ));
				} else {
					builder.append("\tnull");
				}
			}

			VariantMasks masks = abstractProcessor.getMasks(variantSpec, variantMaskBucketHolder);

			//make strings of 000100 so we can just check 'char at'
			//so heterozygous no calls we want, homozygous no calls we don't
			BigInteger heteroMask = masks.heterozygousMask != null? masks.heterozygousMask : masks.heterozygousNoCallMask != null ? masks.heterozygousNoCallMask : null;
			BigInteger homoMask = masks.homozygousMask != null? masks.homozygousMask : null;


			String heteroMaskString = heteroMask != null ? heteroMask.toString(2) : null;
			String homoMaskString = homoMask != null ? homoMask.toString(2) : null;

			// Patient count = (hetero mask | homo mask) & patient mask
			BigInteger heteroOrHomoMask = orNullableMasks(heteroMask, homoMask);
			int patientCount = heteroOrHomoMask == null ? 0 :  (heteroOrHomoMask.and(patientMasks).bitCount() - 4);

			int bitCount = masks.heterozygousMask == null? 0 : (masks.heterozygousMask.bitCount() - 4);
			bitCount += masks.homozygousMask == null? 0 : (masks.homozygousMask.bitCount() - 4);

			//count how many patients have genomic data available
			Integer patientsWithVariantsCount = null;
			if(heteroMaskString != null) {
				patientsWithVariantsCount = heteroMaskString.length() - 4;
			} else if (homoMaskString != null ) {
				patientsWithVariantsCount = homoMaskString.length() - 4;
			} else {
				patientsWithVariantsCount = -1;
			}


			// (patients with/total) in subset   \t   (patients with/total) out of subset.
			builder.append("\t"+ patientCount + "/" + patientIndexMap.size() + "\t" + (bitCount - patientCount) + "/" + (patientsWithVariantsCount - patientIndexMap.size()));

			if (includePatientData) {
				//track the number of subjects without the variant; use a second builder to keep the column order
				StringBuilder patientListBuilder = new StringBuilder();

				for(Integer patientIndex : patientIndexMap.values()) {
					if(heteroMaskString != null && '1' == heteroMaskString.charAt(patientIndex)) {
						patientListBuilder.append("\t0/1");
					}else if(homoMaskString != null && '1' == homoMaskString.charAt(patientIndex)) {
						patientListBuilder.append("\t1/1");
					}else {
						patientListBuilder.append("\t0/0");
					}
				}
				builder.append(patientListBuilder.toString());
			}

			builder.append("\n");
		});


		return builder.toString();
	}

	private BigInteger orNullableMasks(BigInteger heteroMask, BigInteger homoMask) {
		if (heteroMask != null) {
			if (homoMask != null) {
				return heteroMask.or(homoMask);
			}
			return heteroMask;
		} else {
			return homoMask;
		}
	}

	public String[] getHeaderRow(Query query) {
		return null;
	}
}