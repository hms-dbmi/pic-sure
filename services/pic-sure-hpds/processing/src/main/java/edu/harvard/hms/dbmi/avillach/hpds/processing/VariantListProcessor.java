package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;

public class VariantListProcessor extends AbstractProcessor {

	private VariantMetadataIndex metadataIndex;
	
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
	public List<String> runVariantListQuery(Query query) {
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
	public String runVcfExcerptQuery(Query incomingQuery) {
		throw new RuntimeException("Not yet implemented");
	}

}