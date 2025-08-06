package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.collect.Lists;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
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

    private static final Logger log = LoggerFactory.getLogger(MultiValueQueryV3Processor.class);


    @Autowired
    public MultiValueQueryV3Processor(
        QueryExecutor queryExecutor, PhenotypicObservationStore phenotypicObservationStore, @Value("${ID_BATCH_SIZE:0}") int idBatchSize
    ) {
        this.queryExecutor = queryExecutor;
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

    @Override
    public void runQuery(Query query, AsyncResult result) {
        Set<Integer> idList = queryExecutor.getPatientSubsetForQuery(query);
        log.info("Processing " + idList.size() + " rows for result " + result.getId());
        Lists.partition(new ArrayList<>(idList), idBatchSize).forEach(patientIds -> {
            Map<String, Map<Integer, List<String>>> pathToPatientToValueMap = buildResult(query, new TreeSet<>(patientIds));
            // A list of values per concept path, per patient
            List<List<List<String>>> fieldValuesPerPatient =
                patientIds.stream().map(patientId -> Arrays.stream(getHeaderRow(query)).map(field -> {
                    if (PATIENT_ID_FIELD_NAME.equals(field)) {
                        return List.of(patientId.toString());
                    } else {
                        return pathToPatientToValueMap.get(field).get(patientId);
                    }
                }).collect(Collectors.toList())).collect(Collectors.toList());
            result.appendMultiValueResults(fieldValuesPerPatient);
        });
        result.closeWriter();
    }

    private Map<String, Map<Integer, List<String>>> buildResult(Query query, TreeSet<Integer> ids) {
        ConcurrentHashMap<String, Map<Integer, List<String>>> pathToPatientToValueMap = new ConcurrentHashMap<>();
        List<ColumnMeta> columns = query.select().stream().map(queryExecutor.getDictionary()::get).filter(Objects::nonNull).toList();
        List<String> paths = columns.stream().map(ColumnMeta::getName).collect(Collectors.toList());
        int columnCount = paths.size() + 1;

        ArrayList<Integer> columnIndex = queryExecutor.useResidentCubesFirst(paths, columnCount);

        // todo: investigate if the parallel stream will thrash the cache if the number of executors is > number of resident cubes
        columnIndex.parallelStream().forEach((columnId) -> {
            String columnPath = paths.get(columnId - 1);
            Map<Integer, List<String>> patientIdToValueMap = processColumn(ids, columnPath);
            pathToPatientToValueMap.put(columnPath, patientIdToValueMap);
        });

        return pathToPatientToValueMap;
    }

    private Map<Integer, List<String>> processColumn(TreeSet<Integer> patientIds, String path) {

        Map<Integer, List<String>> patientIdToValueMap = new HashMap<>();
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
                        idPointer++;
                    } else {
                        break;
                    }
                }
            }
            return patientIdToValueMap;
        }).orElseGet(Map::of);
    }

    private String getResultField(KeyAndValue<?>[] cubeValues, int idPointer) {
        Comparable<?> value = cubeValues[idPointer].getValue();
        return value.toString();
    }
}
