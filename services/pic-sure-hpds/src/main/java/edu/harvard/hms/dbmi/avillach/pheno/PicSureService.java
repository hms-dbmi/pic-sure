package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.Response;

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
import edu.harvard.hms.dbmi.avillach.pheno.data.AsyncResult;
import edu.harvard.hms.dbmi.avillach.pheno.data.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.pheno.data.Query;
import edu.harvard.hms.dbmi.avillach.picsure.hpds.exception.ValidationException;

@Path("PIC-SURE")
@Produces("application/json")
public class PicSureService implements IResourceRS {

	@Autowired
	private QueryService queryService;
	
	@Autowired
	private QueryRS queryRS;

	private final ObjectMapper mapper = new ObjectMapper();
	
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
		return new SearchResults().setResults(
				searchJson.getQuery()!=null ? 
						allColumns.stream().filter((entry)->{
							String lowerCaseSearchTerm = searchJson.getQuery().toString().toLowerCase();
							return entry.getKey().toLowerCase().contains(lowerCaseSearchTerm) 
									||(
											entry.getValue().isCategorical() 
											&& 
											entry.getValue().getCategoryValues().stream().map(String::toLowerCase).collect(Collectors.toList())
											.contains(lowerCaseSearchTerm));
						}).collect(Collectors.toMap(Entry::getKey, Entry::getValue)) 
						: allColumns).setSearchQuery(searchJson.getQuery().toString());
	}

	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryJson) {
		Query query;
		try {
			query = mapper.readValue(mapper.writeValueAsString(queryJson.getQuery()), Query.class);
			return convertToQueryStatus(queryService.runQuery(query));
		} catch (IOException e) {
			throw new ServerErrorException(500);
		} catch (ValidationException e) {
			QueryStatus status = new QueryStatus();
			status.setStatus(PicSureStatus.ERROR);
			try {
				status.setResultMetadata(mapper.writeValueAsBytes(e.getResult()));
			} catch (JsonProcessingException e1) {
				throw new ServerErrorException(500);
			}
			return status;
		}
	}

	private QueryStatus convertToQueryStatus(AsyncResult entity) {
		QueryStatus status = new QueryStatus();
		status.setDuration(entity.completedTime==0?0:entity.completedTime - entity.queuedTime);;
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
	public Response queryResult(
			@PathParam("resourceQueryId") String queryId, 
			Map<String, String> resourceCredentials) {
		return queryRS.getResultFor(queryId);
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(
			@PathParam("resourceQueryId") String queryId, 
			Map<String, String> resourceCredentials) {
		return convertToQueryStatus(
				queryService.getStatusFor(queryId));
	}

}
