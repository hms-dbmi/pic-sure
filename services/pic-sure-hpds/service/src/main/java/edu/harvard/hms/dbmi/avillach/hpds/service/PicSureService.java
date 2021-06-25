package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.http.entity.ContentType;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.exception.ValidationException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.*;

@Path("PIC-SURE")
@Produces("application/json")
public class PicSureService implements IResourceRS {

	public PicSureService() {
		try {
			countProcessor = new CountProcessor();
			timelineProcessor = new TimelineProcessor();
			variantListProcessor = new VariantListProcessor();
		} catch (ClassNotFoundException | IOException e3) {
			log.error("ClassNotFoundException or IOException caught: ", e3);
		}
		Crypto.loadDefaultKey();
	}
	
	@Autowired
	private QueryService queryService;

	@Autowired
	private QueryRS queryRS;

	private final ObjectMapper mapper = new ObjectMapper();

	private Logger log = Logger.getLogger(PicSureService.class);

	private TimelineProcessor timelineProcessor;
	
	private CountProcessor countProcessor;

	private VariantListProcessor variantListProcessor;
	
	private static final String QUERY_METADATA_FIELD = "queryResultMetadata";
	
	
	@POST
	@Path("/info")
	public ResourceInfo info(QueryRequest request) {
		ResourceInfo info = new ResourceInfo();
		info.setName("PhenoCube v1.0-SNAPSHOT");
		info.setId(UUID.randomUUID());

		try {
			info.setQueryFormats(ImmutableList.of(
					new QueryFormat()
					.setDescription("PhenoCube Query Format")
					.setName("PhenoCube Query Format")
					.setExamples(ImmutableList.of(
							ImmutableMap.of(
									"Demographics and interesting variables for people with high blood pressure", new ObjectMapper().readValue(
											"{\"fields\":[\"\\\\demographics\\\\SEX\\\\\",\"\\\\demographics\\\\WTMEC2YR\\\\\",\"\\\\demographics\\\\WTMEC4YR\\\\\",\"\\\\demographics\\\\area\\\\\",\"\\\\demographics\\\\education\\\\\",\"\\\\examination\\\\blood pressure\\\\60 sec HR (30 sec HR * 2)\\\\\",\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\",\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\",\"\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\",\"\\\\examination\\\\body measures\\\\Head BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Head Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Pelvis BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Spine BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Maximal Calf Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Recumbent Length (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Standing Height (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Subscapular Skinfold (mm)\\\\\"],"
													+ "\"numericFilters\":{\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\":{\"min\":120},\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\":{\"min\":80}}}"
													, Map.class))
							,
							ImmutableMap.of(
									"Demographics and interesting variables for men with high blood pressure who live with a smoker and for whom we have BMI data", 
									ImmutableMap.of(
											"fields", ImmutableList.of(
													"\\demographics\\SEX\\",
													"\\demographics\\WTMEC2YR\\",
													"\\demographics\\WTMEC4YR\\",
													"\\demographics\\area\\",
													"\\demographics\\education\\",
													"\\examination\\blood pressure\\60 sec HR (30 sec HR * 2)\\",
													"\\examination\\blood pressure\\mean diastolic\\",
													"\\examination\\blood pressure\\mean systolic\\",
													"\\examination\\body measures\\Body Mass Index (kg per m**2)\\",
													"\\examination\\body measures\\Head BMD (g per cm^2)\\",
													"\\examination\\body measures\\Head Circumference (cm)\\",
													"\\examination\\body measures\\Lumber Pelvis BMD (g per cm^2)\\",
													"\\examination\\body measures\\Lumber Spine BMD (g per cm^2)\\",
													"\\examination\\body measures\\Maximal Calf Circumference (cm)\\",
													"\\examination\\body measures\\Recumbent Length (cm)\\",
													"\\examination\\body measures\\Standing Height (cm)\\",
													"\\examination\\body measures\\Subscapular Skinfold (mm)\\"
													),
											"requiredFields", ImmutableList.of(
													"\\examination\\body measures\\Body Mass Index (kg per m**2)\\"
													),
											"numericFilters", ImmutableMap.of(
													"\\examination\\blood pressure\\mean systolic\\", ImmutableMap.of("min", 120), 
													"\\examination\\blood pressure\\mean diastolic\\", ImmutableMap.of("min", 80)
													),
											"categoryFilters", ImmutableMap.of(
													"\\demographics\\SEX\\", ImmutableList.of("male"),
													"\\questionnaire\\smoking family\\Does anyone smoke in home?\\", ImmutableList.of("Yes"))
											))))
					.setSpecification(ImmutableMap.of(
							"fields", "A list of field names. Can be any key from the results map returned from the search endpoint of this resource. Unless filters are set, the included fields will be returned for all patients as a sparse matrix.",
							"numericFilters", "A map where each entry maps a field name to an object with min and/or max properties. Patients without a value between the min and max will not be included in the result set.",
							"requiredFields", "A list of field names for which a patient must have a value in order to be inclued in the result set.",
							"categoryFilters", "A map where each entry maps a field name to a list of values to be included in the result set."
							))
					));
		} catch (JsonParseException e) {
			log.error("JsonParseException  caught: ", e);
		} catch (JsonMappingException e) {
			log.error("JsonMappingException  caught: ", e);
		} catch (IOException e) {
			log.error("IOException  caught: ", e);
		}
		// TODO examples, examples, examples, specification
		return info;
	}

	@POST
	@Path("/search")
	public SearchResults search(QueryRequest searchJson) {
		Set<Entry<String, ColumnMeta>> allColumns = queryService.getDataDictionary().entrySet();
		
		//Phenotype Values
		Object phenotypeResults = searchJson.getQuery()!=null ? 
				allColumns.stream().filter((entry)->{
					String lowerCaseSearchTerm = searchJson.getQuery().toString().toLowerCase();
					return entry.getKey().toLowerCase().contains(lowerCaseSearchTerm) 
							||(
									entry.getValue().isCategorical() 
									&& 
									entry.getValue().getCategoryValues().stream().map(String::toLowerCase).collect(Collectors.toList())
									.contains(lowerCaseSearchTerm));
				}).collect(Collectors.toMap(Entry::getKey, Entry::getValue)) 
				: allColumns;

				// Info Values
				Map<String, Map> infoResults = new TreeMap<String, Map>();
				AbstractProcessor.infoStoreColumns.stream().forEach((String infoColumn)->{
					FileBackedByteIndexedInfoStore store = queryService.processor.getInfoStore(infoColumn);
					if(store!=null) {
						String query = searchJson.getQuery().toString();
						String lowerCase = query.toLowerCase();
						boolean storeIsNumeric = store.isContinuous;
						if(store.description.toLowerCase().contains(lowerCase) || store.column_key.toLowerCase().contains(lowerCase)) {
							infoResults.put(infoColumn, ImmutableMap.of("description", store.description, "values", store.isContinuous? new ArrayList<String>() : store.allValues.keys(), "continuous", storeIsNumeric));
						} else {
							List<String> searchResults = store.search(query);
							if( ! searchResults.isEmpty()) {
								infoResults.put(infoColumn, ImmutableMap.of("description", store.description, "values", searchResults, "continuous", storeIsNumeric));
							}
						}
					}
				});

				return new SearchResults().setResults(
						ImmutableMap.of("phenotypes",phenotypeResults, /*"genes", resultMap,*/ "info", infoResults))
						.setSearchQuery(searchJson.getQuery().toString());
	}

	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryJson) {
		Query query;
		QueryStatus queryStatus = new QueryStatus();
		if(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)){
			try {
				query = convertIncomingQuery(queryJson);
				return convertToQueryStatus(queryService.runQuery(query));		
			} catch (IOException e) {
				log.error("IOException caught in query processing:", e);
				throw new ServerErrorException(500);
			} catch (ValidationException e) {
				QueryStatus status = queryStatus;
				status.setStatus(PicSureStatus.ERROR);
				try {
					status.setResourceStatus("Validation failed for query for reason : " + new ObjectMapper().writeValueAsString(e.getResult()));
				} catch (JsonProcessingException e2) {
					log.error("JsonProcessingException  caught: ", e);
				}
					 
		        Map<String, Object> metadata = new HashMap<String, Object>();
		        metadata.put(QUERY_METADATA_FIELD, e.getResult());
		        status.setResultMetadata(metadata);
				return status;
			} catch (ClassNotFoundException e) {
				throw new ServerErrorException(500);
			}  
		} else {
			QueryStatus status = new QueryStatus();
			status.setResourceStatus("Resource is locked.");
			return status;
		}
	}

	private Query convertIncomingQuery(QueryRequest queryJson)
			throws IOException, JsonParseException, JsonMappingException, JsonProcessingException {
		return mapper.readValue(mapper.writeValueAsString(queryJson.getQuery()), Query.class);
	}

	private QueryStatus convertToQueryStatus(AsyncResult entity) {
		QueryStatus status = new QueryStatus();
		status.setDuration(entity.completedTime==0?0:entity.completedTime - entity.queuedTime);
		status.setResourceID(UUID.fromString(entity.id));
		status.setResourceResultId(entity.id);
		status.setResourceStatus(entity.status.name());
		if(entity.status==AsyncResult.Status.SUCCESS) {
			status.setSizeInBytes(entity.stream.estimatedSize());			
		}
		status.setStartTime(entity.queuedTime);
		status.setStatus(entity.status.toPicSureStatus());
		return status;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Produces(MediaType.TEXT_PLAIN_VALUE)
	@Override
	public Response queryResult(
			@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		return queryRS.getResultFor(queryId);
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(
			@PathParam("resourceQueryId") String queryId, 
			QueryRequest request) {
		return convertToQueryStatus(
				queryService.getStatusFor(queryId));
	}
	
	@POST
	@Path("/query/format")
	public Response queryFormat(QueryRequest resultRequest) {
		try {
			//The toString() method here has been overridden to produce a human readable value
			return Response.ok().entity(convertIncomingQuery(resultRequest).toString()).build();
		} catch (IOException e) {
			return Response.ok().entity("An error occurred formatting the query for display: " + e.getLocalizedMessage()).build();
		}
	}

	@POST
	@Path("/query/sync")
	@Produces(MediaType.TEXT_PLAIN_VALUE)
	public Response querySync(QueryRequest resultRequest) {
		if(Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)){
			Query incomingQuery;
			try {
				incomingQuery = convertIncomingQuery(resultRequest);
				log.info("Query Converted");
				switch(incomingQuery.expectedResultType) {
				
				case INFO_COLUMN_LISTING : {
					ArrayList<Map> infoStores = new ArrayList<>();
					AbstractProcessor.infoStoreColumns.stream().forEach((infoColumn)->{
						FileBackedByteIndexedInfoStore store = queryService.processor.getInfoStore(infoColumn);
						if(store!=null) {
							infoStores.add(ImmutableMap.of("key", store.column_key, "description", store.description, "isContinuous", store.isContinuous, "min", store.min, "max", store.max));
						}
					});
					return Response.ok(infoStores, MediaType.APPLICATION_JSON_VALUE).build();
				}
				
				case DATAFRAME : 
				case DATAFRAME_MERGED : {
					QueryStatus status = query(resultRequest);
					while(status.getResourceStatus().equalsIgnoreCase("RUNNING")||status.getResourceStatus().equalsIgnoreCase("PENDING")) {
						status = queryStatus(status.getResourceResultId(), null);
					}
					log.info(status.toString());
					return queryResult(status.getResourceResultId(), null);
					
				}
				
				case CROSS_COUNT : {
					return Response.ok(countProcessor.runCrossCounts(incomingQuery)).header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON).build();
				}
				
				case OBSERVATION_COUNT : {
					return Response.ok(countProcessor.runObservationCount(incomingQuery)).build();
				}
				
				case OBSERVATION_CROSS_COUNT : {
					return Response.ok(countProcessor.runObservationCrossCounts(incomingQuery)).header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON).build();
				}
				
				case VARIANT_COUNT_FOR_QUERY : {
					return Response.ok(countProcessor.runVariantCount(incomingQuery)).header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON).build();
				}
				
				case VARIANT_LIST_FOR_QUERY : {
					return Response.ok(variantListProcessor.runVariantListQuery(incomingQuery)).build();
				}
				
				case VCF_EXCERPT : {
					return Response.ok(variantListProcessor.runVcfExcerptQuery(incomingQuery)).build();
				}
				
				case TIMELINE_DATA : {
					return Response.ok(mapper.writeValueAsString(timelineProcessor.runTimelineQuery(incomingQuery))).build();
				}
				
				default : {
					// The only thing left is counts, this is also the lowest security concern query type so we default to it
					return Response.ok(countProcessor.runCounts(incomingQuery)).build();
				}
				}
			} catch (IOException e) {
				log.error("IOException  caught: ", e);
			}
			return Response.serverError().build();

		} else {
			return Response.status(403).entity("Resource is locked").build();
		}
	}
}
