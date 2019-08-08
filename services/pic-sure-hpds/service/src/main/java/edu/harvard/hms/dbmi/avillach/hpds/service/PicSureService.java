package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
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
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.ValidationException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantsOfInterestProcessor;

@Path("PIC-SURE")
@Produces("application/json")
public class PicSureService implements IResourceRS {

	public PicSureService() {
		try {
			countProcessor = new CountProcessor();
		} catch (ClassNotFoundException | IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}
	}
	
	@Autowired
	private QueryService queryService;

	@Autowired
	private QueryRS queryRS;

	private final ObjectMapper mapper = new ObjectMapper();

	private Logger log = Logger.getLogger(PicSureService.class);

	private VariantsOfInterestProcessor variantsOfInterestProcessor;

	@POST
	@Path("/info")
	public ResourceInfo info(Map<String, String> resourceCredentials) {
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
			// TODO Auto-generated catch block
			e.printStackTrace(); 
		} catch (JsonMappingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
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

				//				// Gene Values
				//				Map<String, GeneSpec> geneResults = new GeneLibrary().geneNameSearch(searchJson.getQuery().toString());
				//				List<Map<String, Object>> resultMap = geneResults.values().parallelStream().map((geneSpec)->{
				//					Set ranges = geneSpec.ranges.asRanges();
				//					return ImmutableMap.of("name", geneSpec.name, "chr", geneSpec.chromosome, "ranges", 
				//							ranges.stream().map((range)->{
				//								Range range2 = (Range) range;
				//								return ImmutableMap.of("start", range2.lowerEndpoint(), "end", range2.upperEndpoint());
				//							}).collect(Collectors.toList()));
				//				}).collect(Collectors.toList());

				// Info Values
				Map<String, Map> infoResults = new TreeMap<String, Map>();
				AbstractProcessor.infoStoreColumns.parallelStream().forEach((String infoColumn)->{
					FileBackedByteIndexedInfoStore store = queryService.processor.getInfoStore(infoColumn);
					if(store!=null) {
						List<String> searchResults = store.search(searchJson.getQuery().toString());
						boolean storeIsNumeric = store.isContinuous;
						if( ! searchResults.isEmpty()) {
							infoResults.put(infoColumn, ImmutableMap.of("description", store.description, "values", searchResults, "continuous", storeIsNumeric));
						}
						if(store.description.toLowerCase().contains(searchJson.getQuery().toString().toLowerCase())) {
							infoResults.put(infoColumn, ImmutableMap.of("description", store.description, "values", store.isContinuous? new ArrayList<String>() : store.allValues.keys(), "continuous", storeIsNumeric));
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
		if(queryJson.getResourceCredentials()!=null && queryJson.getResourceCredentials().containsKey("key")) {
			byte[] keyBytes = queryJson.getResourceCredentials().get("key").trim().getBytes();
			if(keyBytes.length == 32) {
				try {
					Crypto.setKey(keyBytes);
					log.info("Key is set");
					queryService.processor.loadAllDataFiles();
					log.info("Data is loaded");
					queryStatus.setResourceStatus("Resource unlocked.");
					variantsOfInterestProcessor = new VariantsOfInterestProcessor();
				} catch(Exception e) {
					Crypto.setKey(null);
					e.printStackTrace();
					queryStatus.setResourceStatus("Resource locked.");
				}
				return queryStatus;
			}else {
				queryStatus.setResourceStatus("Resource locked.");
				return queryStatus;
			}
		} else if(Crypto.hasKey()){
			try {
				query = convertIncomingQuery(queryJson);
				return convertToQueryStatus(queryService.runQuery(query));		
			} catch (IOException e) {
				throw new ServerErrorException(500);
			} catch (ValidationException e) {
				QueryStatus status = queryStatus;
				status.setStatus(PicSureStatus.ERROR);
				try {
					status.setResourceStatus("Validation failed for query for reason : " + new ObjectMapper().writeValueAsString(e.getResult()));
				} catch (JsonProcessingException e2) {
					// TODO Auto-generated catch block
					e2.printStackTrace();
				}
				try {
					status.setResultMetadata(mapper.writeValueAsBytes(e.getResult()));
				} catch (JsonProcessingException e1) {
					throw new ServerErrorException(500);
				}
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

	private QueryStatus unknownResultTypeQueryStatus() {
		QueryStatus status = new QueryStatus();
		status.setDuration(0);
		status.setExpiration(-1);
		status.setPicsureResultId(null);
		status.setResourceID(null);
		status.setResourceResultId(null);
		status.setResourceStatus("Unsupported result type");
		status.setStatus(PicSureStatus.ERROR);
		return status;
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

	private QueryStatus convertToQueryStatus(int count) {
		QueryStatus status = new QueryStatus();
		status.setResourceStatus("COMPLETE");
		status.setResultMetadata((""+count).getBytes());
		status.setStatus(PicSureStatus.AVAILABLE);
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

	CountProcessor countProcessor;

	@POST
	@Path("/query/sync")
	@Produces(MediaType.TEXT_PLAIN_VALUE)
	public Response querySync(QueryRequest resultRequest) {
		if(Crypto.hasKey()){
			Query incomingQuery;
			try {
				incomingQuery = convertIncomingQuery(resultRequest);
				log.info("Query Converted");
				if(incomingQuery.expectedResultType==ResultType.INFO_COLUMN_LISTING) {
					ArrayList<Map> infoStores = new ArrayList<>();
					AbstractProcessor.infoStoreColumns.stream().forEach((infoColumn)->{
						FileBackedByteIndexedInfoStore store = queryService.processor.getInfoStore(infoColumn);
						if(store!=null) {
							infoStores.add(ImmutableMap.of("key", store.column_key, "description", store.description, "isContinuous", store.isContinuous, "min", store.min, "max", store.max));
						}
					});
					return Response.ok(infoStores, MediaType.APPLICATION_JSON_VALUE).build();
				} else if(incomingQuery.expectedResultType==ResultType.DATAFRAME || incomingQuery.expectedResultType==ResultType.DATAFRAME_MERGED) {
					QueryStatus status = query(resultRequest);
					while(status.getResourceStatus().equalsIgnoreCase("RUNNING")||status.getResourceStatus().equalsIgnoreCase("PENDING")) {
						status = queryStatus(status.getResourceResultId(), null);
					}
					log.info(status.toString());
					return queryResult(status.getResourceResultId(), null);
				} else if (incomingQuery.expectedResultType == ResultType.CROSS_COUNT) {
					return Response.ok(countProcessor.runCrossCounts(incomingQuery)).header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON).build();
//				} else if (incomingQuery.expectedResultType == ResultType.VARIANTS_OF_INTEREST) {
//					return Response.ok(variantsOfInterestProcessor.runVariantsOfInterestQuery(incomingQuery)).header(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON).build();
				} else {
					return Response.ok(countProcessor.runCounts(incomingQuery)).build();				
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (TooManyVariantsException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} 
			return Response.serverError().build();

		} else {
			return Response.status(403).entity("Resource is locked").build();
		}
	}
}
