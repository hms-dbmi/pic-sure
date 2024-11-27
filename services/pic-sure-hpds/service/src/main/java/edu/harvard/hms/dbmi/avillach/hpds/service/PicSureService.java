package edu.harvard.hms.dbmi.avillach.hpds.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.QueryDecorator;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;

@RequestMapping(value = "PIC-SURE", produces = "application/json")
@RestController
public class PicSureService {

	@Autowired
	public PicSureService(QueryService queryService, TimelineProcessor timelineProcessor, CountProcessor countProcessor,
						  VariantListProcessor variantListProcessor, AbstractProcessor abstractProcessor, Paginator paginator,
						  SignUrlService signUrlService, FileSharingService fileSystemService, QueryDecorator queryDecorator,
                          TestDataService testDataService
	) {
		this.queryService = queryService;
		this.timelineProcessor = timelineProcessor;
		this.countProcessor = countProcessor;
		this.variantListProcessor = variantListProcessor;
		this.abstractProcessor = abstractProcessor;
		this.paginator = paginator;
		this.fileSystemService = fileSystemService;
		this.queryDecorator = queryDecorator;
		this.signUrlService = signUrlService;
		Crypto.loadDefaultKey();
        this.testDataService = testDataService;
        Crypto.loadDefaultKey();
	}

	private final QueryService queryService;

	private final ObjectMapper mapper = new ObjectMapper();

	private Logger log = LoggerFactory.getLogger(PicSureService.class);

	private final TimelineProcessor timelineProcessor;

	private final CountProcessor countProcessor;

	private final VariantListProcessor variantListProcessor;

	private final AbstractProcessor abstractProcessor;

	private final Paginator paginator;

	private final SignUrlService signUrlService;

	private final FileSharingService fileSystemService;

	private final QueryDecorator queryDecorator;

	private final TestDataService testDataService;

	private static final String QUERY_METADATA_FIELD = "queryMetadata";
	private static final int RESPONSE_CACHE_SIZE = 50;

	@PostMapping("/info")
	public ResourceInfo info(@RequestBody QueryRequest request) {
		ResourceInfo info = new ResourceInfo();
		info.setName("PhenoCube v1.0-SNAPSHOT");
		info.setId(UUID.randomUUID());

		try {
			info.setQueryFormats(ImmutableList.of(new QueryFormat().setDescription("PhenoCube Query Format")
					.setName("PhenoCube Query Format")
					.setExamples(ImmutableList.of(ImmutableMap.of(
							"Demographics and interesting variables for people with high blood pressure",
							new ObjectMapper().readValue(
									"{\"fields\":[\"\\\\demographics\\\\SEX\\\\\",\"\\\\demographics\\\\WTMEC2YR\\\\\",\"\\\\demographics\\\\WTMEC4YR\\\\\",\"\\\\demographics\\\\area\\\\\",\"\\\\demographics\\\\education\\\\\",\"\\\\examination\\\\blood pressure\\\\60 sec HR (30 sec HR * 2)\\\\\",\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\",\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\",\"\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\",\"\\\\examination\\\\body measures\\\\Head BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Head Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Pelvis BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Lumber Spine BMD (g per cm^2)\\\\\",\"\\\\examination\\\\body measures\\\\Maximal Calf Circumference (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Recumbent Length (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Standing Height (cm)\\\\\",\"\\\\examination\\\\body measures\\\\Subscapular Skinfold (mm)\\\\\"],"
											+ "\"numericFilters\":{\"\\\\examination\\\\blood pressure\\\\mean systolic\\\\\":{\"min\":120},\"\\\\examination\\\\blood pressure\\\\mean diastolic\\\\\":{\"min\":80}}}",
									Map.class)),
							ImmutableMap.of(
									"Demographics and interesting variables for men with high blood pressure who live with a smoker and for whom we have BMI data",
									ImmutableMap.of("fields",
											ImmutableList.of("\\demographics\\SEX\\", "\\demographics\\WTMEC2YR\\",
													"\\demographics\\WTMEC4YR\\", "\\demographics\\area\\",
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
													"\\examination\\body measures\\Subscapular Skinfold (mm)\\"),
											"requiredFields",
											ImmutableList.of(
													"\\examination\\body measures\\Body Mass Index (kg per m**2)\\"),
											"numericFilters",
											ImmutableMap.of("\\examination\\blood pressure\\mean systolic\\",
													ImmutableMap.of("min", 120),
													"\\examination\\blood pressure\\mean diastolic\\",
													ImmutableMap.of("min", 80)),
											"categoryFilters",
											ImmutableMap.of("\\demographics\\SEX\\", ImmutableList.of("male"),
													"\\questionnaire\\smoking family\\Does anyone smoke in home?\\",
													ImmutableList.of("Yes"))))))
					.setSpecification(ImmutableMap.of("fields",
							"A list of field names. Can be any key from the results map returned from the search endpoint of this resource. Unless filters are set, the included fields will be returned for all patients as a sparse matrix.",
							"numericFilters",
							"A map where each entry maps a field name to an object with min and/or max properties. Patients without a value between the min and max will not be included in the result set.",
							"requiredFields",
							"A list of field names for which a patient must have a value in order to be inclued in the result set.",
							"categoryFilters",
							"A map where each entry maps a field name to a list of values to be included in the result set."))));
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

	@PostMapping("/search")
	public SearchResults search(@RequestBody QueryRequest searchJson) {
		Set<Entry<String, ColumnMeta>> allColumns = abstractProcessor.getDictionary().entrySet();

		// Phenotype Values
		Object phenotypeResults = searchJson.getQuery() != null ? allColumns.stream().filter((entry) -> {
			String lowerCaseSearchTerm = searchJson.getQuery().toString().toLowerCase(Locale.ENGLISH);
			return entry.getKey().toLowerCase(Locale.ENGLISH).contains(lowerCaseSearchTerm)
					|| (entry.getValue().isCategorical() && entry.getValue().getCategoryValues().stream()
							.map(String::toLowerCase).collect(Collectors.toList()).contains(lowerCaseSearchTerm));
		}).collect(Collectors.toMap(Entry::getKey, Entry::getValue)) : allColumns;

		// Info Values
		Map<String, Map> infoResults = new TreeMap<String, Map>();
		abstractProcessor.getInfoStoreMeta().stream().forEach(infoColumnMeta -> {
			//FileBackedByteIndexedInfoStore store = abstractProcessor.getInfoStore(infoColumn);
			String query = searchJson.getQuery().toString();
			String lowerCase = query.toLowerCase(Locale.ENGLISH);
			boolean storeIsNumeric = infoColumnMeta.continuous();
			if (infoColumnMeta.description().toLowerCase(Locale.ENGLISH).contains(lowerCase)
					|| infoColumnMeta.key().toLowerCase(Locale.ENGLISH).contains(lowerCase)) {
				infoResults.put(infoColumnMeta.key(),
						ImmutableMap.of("description", infoColumnMeta.description(), "values",
								storeIsNumeric ? new ArrayList<String>() : abstractProcessor.searchInfoConceptValues(infoColumnMeta.key(), ""), "continuous",
								storeIsNumeric));
			}
		});

		return new SearchResults()
				.setResults(
						ImmutableMap.of("phenotypes", phenotypeResults, /* "genes", resultMap, */ "info", infoResults))
				.setSearchQuery(searchJson.getQuery().toString());
	}

	@PostMapping("/query")
	public ResponseEntity<QueryStatus> query(@RequestBody QueryRequest queryJson) {
		if (Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
			try {
				Query query = convertIncomingQuery(queryJson);
				return ResponseEntity.ok(convertToQueryStatus(queryService.runQuery(query)));
			} catch (IOException e) {
				log.error("IOException caught in query processing:", e);
				return ResponseEntity.status(500).build();
			}
		} else {
			QueryStatus status = new QueryStatus();
			status.setResourceStatus("Resource is locked.");
			return ResponseEntity.ok(status);
		}
	}

	private Query convertIncomingQuery(QueryRequest queryJson)
			throws IOException, JsonParseException, JsonMappingException, JsonProcessingException {
		return mapper.readValue(mapper.writeValueAsString(queryJson.getQuery()), Query.class);
	}

	private QueryStatus convertToQueryStatus(AsyncResult entity) {
		QueryStatus status = new QueryStatus();
		status.setDuration(entity.getCompletedTime() == 0 ? 0 : entity.getCompletedTime() - entity.getQueuedTime());
		status.setResourceResultId(entity.getId());
		status.setResourceStatus(entity.getStatus().name());
		if (entity.getStatus() == AsyncResult.Status.SUCCESS) {
			status.setSizeInBytes(entity.getStream().estimatedSize());
		}
		status.setStartTime(entity.getQueuedTime());
		status.setStatus(entity.getStatus().toPicSureStatus());

		Map<String, Object> metadata = new HashMap<String, Object>();
        queryDecorator.setId(entity.getQuery());
        metadata.put("picsureQueryId", UUIDv5.UUIDFromString(entity.getQuery().getId()));
		status.setResultMetadata(metadata);
		return status;
	}

	@PostMapping(value = "/query/{resourceQueryId}/result")
	public ResponseEntity queryResult(@PathVariable("resourceQueryId") UUID queryId, @RequestBody QueryRequest resultRequest) throws IOException {
		AsyncResult result = queryService.getResultFor(queryId.toString());
		if (result == null) {
			return ResponseEntity.status(404).build();
		}
		if (result.getStatus() == AsyncResult.Status.SUCCESS) {
			result.open();
			return ResponseEntity.ok()
					.contentType(result.getResponseType())
					.body(new InputStreamResource(result.getStream()));
		} else {
			return ResponseEntity.status(400).body("Status : " + result.getStatus().name());
		}
	}
    private Optional<String> roundTripUUID(String uuid) {
        try {
            return Optional.ofNullable(UUID.fromString(uuid).toString());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @PostMapping("/write/{dataType}")
    public ResponseEntity writeQueryResult(
            @RequestBody() Query query, @PathVariable("dataType") String datatype
    ) {
        if ("test_upload".equals(datatype)) {
            return testDataService.uploadTestFile(query.getPicSureId()) ?
                    ResponseEntity.ok().build() : ResponseEntity.status(500).build();
        }
        if (roundTripUUID(query.getPicSureId()).map(id -> !id.equalsIgnoreCase(query.getPicSureId())).orElse(false)) {
            return ResponseEntity
                    .status(400)
					.body("The query pic-sure ID is not a UUID");
        }
        if (query.getExpectedResultType() != ResultType.DATAFRAME_TIMESERIES) {
            return ResponseEntity
                    .status(400)
					.body("The write endpoint only writes time series dataframes. Fix result type.");
        }
        String hpdsQueryID;
        try {
            QueryStatus queryStatus = convertToQueryStatus(queryService.runQuery(query));
            String status = queryStatus.getResourceStatus();
            hpdsQueryID = queryStatus.getResourceResultId();
            while ("RUNNING".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)) {
                Thread.sleep(10000); // Yea, this is not restful. Sorry.
                status = convertToQueryStatus(queryService.getStatusFor(hpdsQueryID)).getResourceStatus();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Error waiting for response", e);
            return ResponseEntity.internalServerError().build();
        }

        AsyncResult result = queryService.getResultFor(hpdsQueryID);
        // the queryResult has this DIY retry logic that blocks a system thread.
        // I'm not going to do that here. If the service can't find it, you get a 404.
        // Retry it client side.
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        if (AsyncResult.Status.ERROR.equals(result.getStatus())) {
            return ResponseEntity.status(500).build();
        }
        if (!AsyncResult.Status.SUCCESS.equals(result.getStatus())) {
            return ResponseEntity.status(503).build(); // 503 = unavailable
        }

        // at least for now, this is going to block until we finish writing
        // Not very restful, but it will make this API very easy to consume
        boolean success = false;
        query.setId(hpdsQueryID);
        if ("phenotypic".equals(datatype)) {
            success = fileSystemService.createPhenotypicData(query);
        } else if ("genomic".equals(datatype)) {
            success = fileSystemService.createGenomicData(query);
        }
        return success ? ResponseEntity.ok().build() : ResponseEntity.internalServerError().build();
    }

	@PostMapping(value = "/query/{resourceQueryId}/signed-url")
	public ResponseEntity querySignedURL(@PathVariable("resourceQueryId") UUID queryId, @RequestBody QueryRequest resultRequest) throws IOException {
		AsyncResult result = queryService.getResultFor(queryId.toString());
		if (result == null) {
			return ResponseEntity.status(404).build();
		}
		if (result.getStatus() == AsyncResult.Status.SUCCESS) {
			File file = result.getFile();
			signUrlService.uploadFile(file, file.getName());
			String presignedGetUrl = signUrlService.createPresignedGetUrl(file.getName());
			log.info("Presigned url: " + presignedGetUrl);
			return ResponseEntity.ok()
					.contentType(MediaType.APPLICATION_JSON)
					.body(new SignedUrlResponse(presignedGetUrl));
		} else {
			return ResponseEntity.status(400).body("Status : " + result.getStatus().name());
		}
	}

	@PostMapping("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(@PathVariable("resourceQueryId") UUID queryId, @RequestBody QueryRequest request) {
		return convertToQueryStatus(queryService.getStatusFor(queryId.toString()));
	}

	@PostMapping("/query/format")
	public ResponseEntity queryFormat(@RequestBody QueryRequest resultRequest) {
		try {
			// The toString() method here has been overridden to produce a human readable
			// value
			return ResponseEntity.ok(convertIncomingQuery(resultRequest).toString());
		} catch (IOException e) {
			return ResponseEntity.status(500).body("An error occurred formatting the query for display: " + e.getLocalizedMessage());
		}
	}

	@PostMapping(value = "/query/sync", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity querySync(@RequestBody QueryRequest resultRequest) {
		if (Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
			try {
				return _querySync(resultRequest);
			} catch (IOException e) {
				log.error("IOException  caught: ", e);
				return ResponseEntity.status(500).build();
			}
		} else {
			return ResponseEntity.status(403).body("Resource is locked");
		}
	}

	@GetMapping("/search/values/")
	public PaginatedSearchResult<String> searchGenomicConceptValues(
			@RequestParam("genomicConceptPath") String genomicConceptPath,
			@RequestParam("query") String query,
			@RequestParam("page") int page,
			@RequestParam("size") int size
	) {
		if (page < 1) {
			throw new IllegalArgumentException("Page must be greater than 0");
		}
		if (size < 1) {
			throw new IllegalArgumentException("Size must be greater than 0");
		}
		final List<String> matchingValues = abstractProcessor.searchInfoConceptValues(genomicConceptPath, query);
		return paginator.paginate(matchingValues, page, size);
	}

	private ResponseEntity _querySync(QueryRequest resultRequest) throws IOException {
		Query incomingQuery;
		incomingQuery = convertIncomingQuery(resultRequest);
		log.info("Query Converted");
		switch (incomingQuery.getExpectedResultType()) {

		case INFO_COLUMN_LISTING:
			List<InfoColumnMeta> infoColumnMeta = abstractProcessor.getInfoStoreMeta();
			return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(infoColumnMeta);

		case DATAFRAME:
		case SECRET_ADMIN_DATAFRAME:
		case DATAFRAME_TIMESERIES:
			QueryStatus status = query(resultRequest).getBody();
			while (status.getResourceStatus().equalsIgnoreCase("RUNNING")
					|| status.getResourceStatus().equalsIgnoreCase("PENDING")) {
				status = queryStatus(UUID.fromString(status.getResourceResultId()), null);
			}
			log.info(status.toString());

			AsyncResult result = queryService.getResultFor(status.getResourceResultId());
			if (result.getStatus() == AsyncResult.Status.SUCCESS) {
				result.getStream().open();
				return queryOkResponse(new String(result.getStream().readAllBytes(), StandardCharsets.UTF_8), incomingQuery, MediaType.TEXT_PLAIN);
			}
			return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON).body("Status : " + result.getStatus().name());

		case CROSS_COUNT:
			return queryOkResponse(countProcessor.runCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		case CATEGORICAL_CROSS_COUNT:
			return queryOkResponse(countProcessor.runCategoryCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		case CONTINUOUS_CROSS_COUNT:
			return queryOkResponse(countProcessor.runContinuousCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		case OBSERVATION_COUNT:
			return queryOkResponse(String.valueOf(countProcessor.runObservationCount(incomingQuery)), incomingQuery, MediaType.TEXT_PLAIN);

		case OBSERVATION_CROSS_COUNT:
			return queryOkResponse(countProcessor.runObservationCrossCounts(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		case VARIANT_COUNT_FOR_QUERY:
			return queryOkResponse(countProcessor.runVariantCount(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		case VARIANT_LIST_FOR_QUERY:
			return queryOkResponse(variantListProcessor.runVariantListQuery(incomingQuery), incomingQuery, MediaType.TEXT_PLAIN);

		case VCF_EXCERPT:
			return queryOkResponse(variantListProcessor.runVcfExcerptQuery(incomingQuery, true), incomingQuery, MediaType.TEXT_PLAIN);

		case AGGREGATE_VCF_EXCERPT:
			return queryOkResponse(variantListProcessor.runVcfExcerptQuery(incomingQuery, false), incomingQuery, MediaType.TEXT_PLAIN);

		case TIMELINE_DATA:
			return queryOkResponse(mapper.writeValueAsString(timelineProcessor.runTimelineQuery(incomingQuery)),
					incomingQuery, MediaType.TEXT_PLAIN);

		case COUNT:
			return queryOkResponse(String.valueOf(countProcessor.runCounts(incomingQuery)), incomingQuery, MediaType.TEXT_PLAIN);

		case PATIENT_AND_CONCEPT_COUNT:
			return queryOkResponse(countProcessor.runPatientAndConceptCount(incomingQuery), incomingQuery, MediaType.APPLICATION_JSON);

		default:
			// no valid type
			return ResponseEntity.status(500).build();
		}
	}

	private ResponseEntity queryOkResponse(Object obj, Query incomingQuery) {
        queryDecorator.setId(incomingQuery);
        HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.set(QUERY_METADATA_FIELD, UUIDv5.UUIDFromString(incomingQuery.toString()).toString());
		return new ResponseEntity<>(obj, responseHeaders, HttpStatus.OK);
	}
	private ResponseEntity queryOkResponse(Object obj, Query incomingQuery, MediaType mediaType) {
        queryDecorator.setId(incomingQuery);
        HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.set(QUERY_METADATA_FIELD, UUIDv5.UUIDFromString(incomingQuery.toString()).toString());
		return ResponseEntity
				.ok()
				.contentType(mediaType)
				.headers(responseHeaders)
				.body(obj);
	}
}
