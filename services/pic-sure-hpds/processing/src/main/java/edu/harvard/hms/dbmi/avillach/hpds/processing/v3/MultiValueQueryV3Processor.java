package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.collect.Lists;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeSeriesConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.processing.MultiValueQueryProcessor} and updated to use new
 * Query entity
 */
@Component
public class MultiValueQueryV3Processor implements HpdsV3Processor {

    public static final String PATIENT_ID_FIELD_NAME = "patient_id";
    private final int idBatchSize;
    private final QueryExecutor queryExecutor;

    private final PhenotypicObservationStore phenotypicObservationStore;

    private final TimeSeriesConversionService conversionService;

    private static final Logger log = LoggerFactory.getLogger(MultiValueQueryV3Processor.class);


    @Autowired
    public MultiValueQueryV3Processor(
        QueryExecutor queryExecutor, PhenotypicObservationStore phenotypicObservationStore, TimeSeriesConversionService conversionService,
        @Value("${ID_BATCH_SIZE:0}") int idBatchSize
    ) {
        this.queryExecutor = queryExecutor;
        this.conversionService = conversionService;
        this.idBatchSize = idBatchSize;
        this.phenotypicObservationStore = phenotypicObservationStore;
    }

    @Override
    public String[] getHeaderRow(Query query) {
        String[] header = new String[query.select().size() + 1];
        header[0] = PATIENT_ID_FIELD_NAME;
        System.arraycopy(query.select().toArray(new String[0]), 0, header, 1, query.select().size());
        return header;
    }

    /**
     * Writes one row per matching patient, holding every value the patient has for each selected concept. For a
     * {@link ResultType#DATAFRAME_PFB} query, the selected concepts are first scanned for dated observations among the matching patients,
     * and the result stream is told which concepts are dated before the first row is appended. Rows of such a query carry the ISO-8601 time
     * of each value of a dated concept. Every other query, and a PFB query with no dated concept, appends values only.
     *
     * @param query the query to run
     * @param result the result that receives the rows and is closed when the last batch is written
     */
    @Override
    public void runQuery(Query query, AsyncResult result) {
        Set<Integer> idList = queryExecutor.getPatientSubsetForQuery(query);
        log.info("Processing " + idList.size() + " rows for result " + result.getId());
        Set<String> datedConcepts = findDatedConcepts(query, idList);
        if (!datedConcepts.isEmpty()) {
            result.getStream().declareTimestampedFields(datedConcepts);
        }
        Lists.partition(new ArrayList<>(idList), idBatchSize).forEach(patientIds -> {
            Map<String, ColumnObservations> pathToObservations = buildResult(query, new TreeSet<>(patientIds), datedConcepts);
            // A list of values per concept path, per patient
            List<List<List<String>>> fieldValuesPerPatient =
                patientIds.stream().map(patientId -> Arrays.stream(getHeaderRow(query)).map(field -> {
                    if (PATIENT_ID_FIELD_NAME.equals(field)) {
                        return List.of(patientId.toString());
                    } else {
                        ColumnObservations observations = pathToObservations.get(field);
                        if (observations != null) {
                            return observations.values().get(patientId);
                        }
                        return new ArrayList<String>();
                    }
                }).collect(Collectors.toList())).collect(Collectors.toList());
            if (datedConcepts.isEmpty()) {
                result.appendMultiValueResults(fieldValuesPerPatient);
            } else {
                result.getStream()
                    .appendMultiValueResults(fieldValuesPerPatient, fieldTimestampsPerPatient(query, patientIds, pathToObservations));
            }
        });
        result.closeWriter();
    }

    /**
     * Finds the selected concepts that have at least one dated observation for a patient in the set. Runs only for a
     * {@link ResultType#DATAFRAME_PFB} query. Each cube is read through the same store, in the same resident-first order and with the same
     * parallelism as the batch build, and its scan stops at the first dated observation that belongs to a patient in the set.
     *
     * @param query the query being run
     * @param patientIds every patient the query matches, across all batches
     * @return the dated concept paths, empty for any other result type or when no patient matches
     */
    private Set<String> findDatedConcepts(Query query, Set<Integer> patientIds) {
        if (query.expectedResultType() != ResultType.DATAFRAME_PFB || patientIds.isEmpty()) {
            return Set.of();
        }
        List<String> paths = selectedPaths(query);
        Set<String> datedConcepts = ConcurrentHashMap.newKeySet();
        queryExecutor.useResidentCubesFirst(paths, paths.size() + 1).stream().distinct().parallel().forEach(columnId -> {
            String columnPath = paths.get(columnId - 1);
            if (hasDatedObservation(columnPath, patientIds)) {
                datedConcepts.add(columnPath);
            }
        });
        return datedConcepts;
    }

    private boolean hasDatedObservation(String path, Set<Integer> patientIds) {
        return phenotypicObservationStore.getCube(path).map(cube -> {
            for (KeyAndValue<?> observation : cube.sortedByKey()) {
                if (isDated(observation.getTimestamp()) && patientIds.contains(observation.getKey())) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    /**
     * Tells whether a stored timestamp is a real time. An observation loaded without a date holds null or {@link Long#MIN_VALUE}. Epoch
     * zero is a real time.
     *
     * @param timestamp the timestamp of an observation, in epoch milliseconds
     * @return true when the observation is dated
     */
    private static boolean isDated(Long timestamp) {
        return timestamp != null && timestamp != Long.MIN_VALUE;
    }

    /**
     * Lays out the timestamps of one batch in the shape of its values: one entry per patient, each holding one entry per header field.
     *
     * @param query the query being run
     * @param patientIds the patients of this batch, in row order
     * @param pathToObservations the observations of this batch by concept path
     * @return for each patient and each field, the timestamps aligned with that field's values, or null for an undated field and for a
     *         patient with no values
     */
    private List<List<List<String>>> fieldTimestampsPerPatient(
        Query query, List<Integer> patientIds, Map<String, ColumnObservations> pathToObservations
    ) {
        String[] headerRow = getHeaderRow(query);
        List<List<List<String>>> fieldTimestampsPerPatient = new ArrayList<>(patientIds.size());
        for (Integer patientId : patientIds) {
            List<List<String>> fieldTimestamps = new ArrayList<>(headerRow.length);
            for (String field : headerRow) {
                ColumnObservations observations = pathToObservations.get(field);
                fieldTimestamps.add(observations == null ? null : observations.timestamps().get(patientId));
            }
            fieldTimestampsPerPatient.add(fieldTimestamps);
        }
        return fieldTimestampsPerPatient;
    }

    private List<String> selectedPaths(Query query) {
        List<ColumnMeta> columns = query.select().stream().map(queryExecutor.getDictionary()::get).filter(Objects::nonNull).toList();
        return columns.stream().map(ColumnMeta::getName).collect(Collectors.toList());
    }

    private Map<String, ColumnObservations> buildResult(Query query, TreeSet<Integer> ids, Set<String> datedConcepts) {
        ConcurrentHashMap<String, ColumnObservations> pathToObservations = new ConcurrentHashMap<>();
        List<String> paths = selectedPaths(query);
        int columnCount = paths.size() + 1;

        ArrayList<Integer> columnIndex = queryExecutor.useResidentCubesFirst(paths, columnCount);

        // todo: investigate if the parallel stream will thrash the cache if the number of executors is > number of resident cubes
        columnIndex.stream().distinct().parallel().forEach((columnId) -> {
            String columnPath = paths.get(columnId - 1);
            pathToObservations.put(columnPath, processColumn(ids, columnPath, datedConcepts.contains(columnPath)));
        });

        return pathToObservations;
    }

    private ColumnObservations processColumn(TreeSet<Integer> patientIds, String path, boolean dated) {

        Map<Integer, List<String>> patientIdToValueMap = new HashMap<>();
        Map<Integer, List<String>> patientIdToTimestampMap = new HashMap<>();
        Optional<PhenoCube<?>> cubeOptional = phenotypicObservationStore.getCube(path);

        return cubeOptional.map(cube -> {
            KeyAndValue<?>[] cubeValues = cube.sortedByKey();

            int idPointer = 0;
            for (int patientId : patientIds) {
                while (idPointer < cubeValues.length) {
                    int key = cubeValues[idPointer].getKey();
                    if (key < patientId) {
                        idPointer++;
                    } else if (key == patientId) {
                        String value = getResultField(cubeValues, idPointer);
                        patientIdToValueMap.computeIfAbsent(patientId, k -> new ArrayList<>()).add(value);
                        if (dated) {
                            patientIdToTimestampMap.computeIfAbsent(patientId, k -> new ArrayList<>())
                                .add(toIsoTimestamp(cubeValues[idPointer].getTimestamp()));
                        }
                        idPointer++;
                    } else {
                        break;
                    }
                }
            }
            return new ColumnObservations(patientIdToValueMap, patientIdToTimestampMap);
        }).orElseGet(() -> new ColumnObservations(Map.of(), Map.of()));
    }

    private String getResultField(KeyAndValue<?>[] cubeValues, int idPointer) {
        Comparable<?> value = cubeValues[idPointer].getValue();
        return value.toString();
    }

    private String toIsoTimestamp(Long timestamp) {
        return isDated(timestamp) ? conversionService.toISOString(timestamp) : null;
    }

    /**
     * The observations of one concept for one batch of patients.
     *
     * @param values each patient's values, in cube order
     * @param timestamps each patient's ISO-8601 timestamps, index-aligned with {@code values}, with null for an undated observation. Empty
     *        when the concept is not dated.
     */
    private record ColumnObservations(Map<Integer, List<String>> values, Map<Integer, List<String>> timestamps) {
    }
}
