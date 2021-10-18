package edu.harvard.hms.dbmi.avillach.resource.passthru;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;

@Path("/passthru")
@Produces("application/json")
@Consumes("application/json")
public class SearchResourceRS implements IResourceRS {

	private static final String BEARER_STRING = "Bearer ";

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final Logger logger = LoggerFactory.getLogger(SearchResourceRS.class);

	@Inject
	private ApplicationProperties properties;
	@Inject
	private HttpClient httpClient;
	
	@Inject
	ResourceRepository resourceRepo;

	@Inject
	ResourceWebClient resourceWebClient;
	
	/**
	 * this store the merged ontologies from the backing resources 
	 */
	private static Map<String, SearchColumnMeta> mergedPhenotypeOntologies;
	
	private static Map<String, SearchColumnMeta> mergedInfoStoreColumns;

	public SearchResourceRS() {
		logger.debug("default constructor called");
		updateOntologies();
	}

	@Inject
	public SearchResourceRS(ApplicationProperties applicationProperties, HttpClient httpClient) {
		this.properties = applicationProperties;
		this.httpClient = httpClient;
		
		logger.debug("Two param constructor called");
		updateOntologies();
	}

	@POST
	@Path("/info")
	public ResourceInfo info(QueryRequest infoRequest) {
		ResourceInfo info = new ResourceInfo();
		info.setName("Search Resource - no queries accepted");
		info.setId(UUID.randomUUID());
		
		return info;
	}

	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryRequest) {
		logger.debug("Calling Search Resource query()");
		throw new UnsupportedOperationException("Query is not implemented in this resource.");
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		logger.debug("Calling Search Resource queryResult()");
		throw new UnsupportedOperationException("Query result is not implemented in this resource.");
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusRequest) {
		logger.debug("Calling Search Resource queryStatus()");
		throw new UnsupportedOperationException("Query status is not implemented in this resource.");
	}

	@POST
	@Path("/query/sync")
	@Override
	public Response querySync(QueryRequest queryRequest) {
		logger.debug("Calling Search Resource querySync()");
		throw new UnsupportedOperationException("Query sync is not implemented in this resource.");
	}

	@POST
	@Path("/search")
	public SearchResults search(QueryRequest searchRequest) {
		
		//pheno values
		Map<String, SearchColumnMeta> phenotypeResults = searchRequest.getQuery()!=null ? 
			mergedPhenotypeOntologies.entrySet().stream().filter((entry)->{
				String lowerCaseSearchTerm = searchRequest.getQuery().toString().toLowerCase();
				return entry.getKey().toLowerCase().contains(lowerCaseSearchTerm) 
					||(
					entry.getValue().isCategorical() 
					&& 
					entry.getValue().getCategoryValues().stream().map(String::toLowerCase).collect(Collectors.toList())
					.contains(lowerCaseSearchTerm));
		}).collect(Collectors.toMap(Entry::getKey, Entry::getValue)) 
		: mergedPhenotypeOntologies;

		// Info Values
		Map<String, SearchColumnMeta> infoResults = searchRequest.getQuery()!=null ? 
			mergedInfoStoreColumns.entrySet().stream().filter((entry)->{
				String lowerCaseSearchTerm = searchRequest.getQuery().toString().toLowerCase();
				return entry.getKey().toLowerCase().contains(lowerCaseSearchTerm) 
					||(
					entry.getValue().isCategorical() 
					&& 
					entry.getValue().getCategoryValues().stream().map(String::toLowerCase).collect(Collectors.toList())
					.contains(lowerCaseSearchTerm));
		}).collect(Collectors.toMap(Entry::getKey, Entry::getValue))
		: mergedInfoStoreColumns;
					
		
		return new SearchResults().setResults(
				ImmutableMap.of("phenotypes",phenotypeResults, /*"genes", resultMap,*/ "info", infoResults))
				.setSearchQuery(searchRequest.getQuery().toString());
		
	}

	
	private void updateOntologies() {
	
		ConcurrentHashMap<String, SearchColumnMeta> newPhenotypes = new ConcurrentHashMap<String, SearchColumnMeta>();
		ConcurrentHashMap<String, SearchColumnMeta> newInfoColumns = new ConcurrentHashMap<String, SearchColumnMeta>();
		
		resourceRepo.list().parallelStream().forEach(resource -> {
			//filter out the search resource itself - use a flag for clarity
			if(resource.getHidden()) {
				logger.debug("skipping update of hidden resource " + resource.getName());
				return;
			}
			logger.debug("Updating ontology for resource " + resource.getName());
			
			//empty search should return all results
			SearchResults search = resourceWebClient.search(resource.getResourceRSPath(), new QueryRequest());
			Map<String, Object> resourceResults = (Map<String, Object>)search.getResults();
				
			//Pheno results
			//this may also return a map?
			Set<Entry<String, SearchColumnMeta>> phenoResults = (Set<Entry<String, SearchColumnMeta>>) resourceResults.get("phenotype");
			phenoResults.stream().forEach(entry -> {
				//merge the metadata fields (max/min, concept values, etc.)
				SearchColumnMeta conceptMeta = updatePhenoMetaData(entry.getValue(), newPhenotypes.get(entry.getKey()), resource.getName());
				
				if(conceptMeta != null) {
					conceptMeta.getResourceAvailability().add(resource.getName());
				}
				newPhenotypes.put(entry.getKey(), conceptMeta);
			});
			
			
			//InfoColumns
			Map<String, Map> infoResults = (Map<String, Map>) resourceResults.get("info");
			infoResults.entrySet().stream().forEach(entry -> {
				//merge the metadata fields (max/min, concept values, etc.)
				SearchColumnMeta conceptMeta = updateInfoMetaData(entry, newInfoColumns.get(entry.getKey()), resource.getName());
				
				if(conceptMeta != null) {
					conceptMeta.getResourceAvailability().add(resource.getName());
				}
				newInfoColumns.put(entry.getKey(), conceptMeta);
			});
			
			logger.debug("finished updating ontology for resource " + resource.getName());
		});
		
		mergedPhenotypeOntologies = newPhenotypes;
		mergedInfoStoreColumns = newInfoColumns;
	}
	
	private SearchColumnMeta updateInfoMetaData(Entry mapEntry, SearchColumnMeta searchColumnMeta, String resourceName) {
		//String - > Object
		Map value = (Map) mapEntry.getValue();
		if(value == null) {
			return searchColumnMeta;
		}
		
		if(searchColumnMeta == null) {
			searchColumnMeta = new SearchColumnMeta();
		}
		
		//"description", "values", "continuous"
		if(value.containsKey("description")) {
			if ( searchColumnMeta.getDescription() == null || searchColumnMeta.getDescription().isBlank()) {
				searchColumnMeta.setDescription((String) value.get("description"));
			} else {
				if( !value.get("description").equals(searchColumnMeta.getDescription()) ) {
					logger.warn("Conflicting descriptions in info column " + mapEntry.getKey() + " from resource " + resourceName
					+ " already have description from " + Arrays.deepToString(searchColumnMeta.getResourceAvailability().toArray()));
				}
			}
		}

//		this is a bit weird because pheotype and genotype columns use inverted values "continuous"  vs. "Categorical"
		//we are sharing a datatype so we just use 'categorical'
		if(value.containsKey("continuous")) {
			if ( searchColumnMeta.isCategorical() == null) {
				searchColumnMeta.setCategorical( ! ((Boolean)value.get("continuous")));
			} else {
				
				//validate using NOT XOR (since the values are flipped; same value means disagreement)
				if( ! ((Boolean)value.get("continuous") ^ searchColumnMeta.isCategorical()) ) {
					logger.warn("Conflicting 'continuous' flags in info column " + mapEntry.getKey() + " from resource " + resourceName
					+ " already have flag from " + Arrays.deepToString(searchColumnMeta.getResourceAvailability().toArray()));
				
					//if we are confused about the categorical/numeric nature of this column, don't go farther
					return searchColumnMeta;
				}
			}
		}
			
		if(value.containsKey("values")) {
			if ( searchColumnMeta.getCategoryValues() == null) {
				//hashset enforces uniqueness, so we don't need to worry about comparison
				searchColumnMeta.setCategoryValues( new HashSet<String>((List<String>)value.get("values")));
			} else {
				searchColumnMeta.getCategoryValues().addAll((List<String>)value.get("values"));
			}
		}
		
		searchColumnMeta.getResourceAvailability().add(resourceName);
		return searchColumnMeta;
	}

	/**
	 * compare two sets of concept meta data and return a set of ranges or values encompassing all values
	 * @param conceptMeta
	 * @param value
	 * @return
	 */
	private SearchColumnMeta updatePhenoMetaData(SearchColumnMeta conceptMeta, SearchColumnMeta searchColumnMeta, String resourceName) {

		if(conceptMeta == null) {
			return searchColumnMeta;
		}
		
		if(searchColumnMeta == null) {
			searchColumnMeta = new SearchColumnMeta();
		}
	
		
		if(searchColumnMeta.isCategorical() == null) {
			searchColumnMeta.setCategorical(conceptMeta.isCategorical());
		}	else {
			//for this boolean, don't update, just log a warning
			logger.warn("Conflicting 'categorical' flags in phenotype concept " + conceptMeta.getName() + " from resource " + resourceName
			+ " already have flag from " + Arrays.deepToString(searchColumnMeta.getResourceAvailability().toArray()));
			//if we are confused about the categorical/numeric nature of this column, don't go farther
			return searchColumnMeta;
		}
		
		if(searchColumnMeta.isCategorical()) {
			if ( searchColumnMeta.getCategoryValues() == null) {
				//hashset enforces uniqueness, so we don't need to worry about comparison
				searchColumnMeta.setCategoryValues( conceptMeta.getCategoryValues());
			} else {
				searchColumnMeta.getCategoryValues().addAll(conceptMeta.getCategoryValues());
			}
		} else {
		
			if ( searchColumnMeta.getMin() == null || searchColumnMeta.getMin() > conceptMeta.getMin()) {
				searchColumnMeta.setMin( conceptMeta.getMin());
			}
			
			if ( searchColumnMeta.getMax() == null || searchColumnMeta.getMax() < conceptMeta.getMax()) {
				searchColumnMeta.setMax( conceptMeta.getMax());
			}
		}
		
		searchColumnMeta.getResourceAvailability().add(resourceName);
		return searchColumnMeta;
	}

	
	//I think we can do this through the DB using wildfly's connection
	private SearchResults pullRemoteOntology(String url, String resourceId) {
		
		QueryRequest searchRequest = new QueryRequest();
		searchRequest.setQuery("");  //empty search string should return all results
		searchRequest.setResourceUUID(UUID.fromString(resourceId));
		// I don't think we need resource credentials - nc
		//	searchRequest.setResourceCredentials(searchRequest.getResourceCredentials());
		
		String pathName = "/search/" + resourceId;
		try {
			String payload = objectMapper.writeValueAsString(searchRequest);
			HttpResponse response = httpClient.retrievePostResponse(
					httpClient.composeURL(url, pathName), createAuthHeader(), payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} did not return a 200: {} {} ", url, pathName,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				httpClient.throwResponseError(response, url);
			}
			return httpClient.readObjectFromResponse(response, SearchResults.class);
		} catch (IOException e) {
			// Note: this shouldn't ever happen
			logger.error("Error encoding search payload", e);
			throw new ApplicationException(
					"Error encoding search for resource with id " + searchRequest.getResourceUUID());
		}	
	}
	
	private Header[] createAuthHeader() {
		return new Header[] {
				new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken()) };
	}
}
