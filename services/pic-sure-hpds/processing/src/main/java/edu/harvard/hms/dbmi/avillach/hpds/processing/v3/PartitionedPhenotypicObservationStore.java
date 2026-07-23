package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.UserRequestContext;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PartitionedPhenotypicObservationStore {

    private static final Logger log = LoggerFactory.getLogger(PartitionedPhenotypicObservationStore.class);

    private final UserRequestContext userRequestContext;

    private final Map<String, PhenotypicObservationStore> phenotypicPartitions;

    @Autowired
    public PartitionedPhenotypicObservationStore(
        UserRequestContext userRequestContext, @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory
    ) {
        this.userRequestContext = userRequestContext;

        try (Stream<Path> stream = Files.list(Path.of(hpdsDataDirectory))) {
            List<Path> subdirectories = stream.filter(Files::isDirectory)
                .filter(subdirectory -> !subdirectory.equals(Path.of(hpdsDataDirectory))).collect(Collectors.toList());

            Map<String, PhenotypicObservationStore> phenotypicPartitions = new HashMap<>();

            for (Path subdirectory : subdirectories) {
                String partitionName = subdirectory.getFileName().toString();
                PhenotypeMetaStore phenotypeMetaStore = new PhenotypeMetaStore(subdirectory.toString(), 500);
                PhenotypicObservationStore phenotypicObservationStore =
                    new PhenotypicObservationStore(phenotypeMetaStore, subdirectory.toString(), 1000);
                phenotypicPartitions.put(partitionName, phenotypicObservationStore);
            }

            this.phenotypicPartitions = Map.copyOf(phenotypicPartitions);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Integer> getKeysForRange(String conceptPath, Double min, Double max) {
        // todo: disallow this by default
        if (userRequestContext.getUserConsents().isEmpty()) {
            Set<Integer> patientIds = phenotypicPartitions.values().stream()
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getKeysForRange(conceptPath, min, max).stream())
                .collect(Collectors.toSet());
            return patientIds;
        } else {
            Set<Integer> patientIds = userRequestContext.getUserConsents().stream().map(phenotypicPartitions::get)
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getKeysForRange(conceptPath, min, max).stream())
                .collect(Collectors.toSet());
            return patientIds;
        }
    }

    public Set<Integer> getKeysForValues(String conceptPath, Collection<String> values) {
        // todo: disallow this by default
        if (userRequestContext.getUserConsents().isEmpty()) {
            Set<Integer> patientIds = phenotypicPartitions.values().stream()
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getKeysForValues(conceptPath, values).stream())
                .collect(Collectors.toSet());
            return patientIds;
        } else {
            Set<Integer> patientIds = userRequestContext.getUserConsents().stream().map(phenotypicPartitions::get)
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getKeysForValues(conceptPath, values).stream())
                .collect(Collectors.toSet());
            return patientIds;
        }
    }

    public List<Integer> getAllKeys(String conceptPath) {
        // todo: disallow this by default
        if (userRequestContext.getUserConsents().isEmpty()) {
            List<Integer> patientIds = phenotypicPartitions.values().stream()
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getAllKeys(conceptPath).stream())
                .collect(Collectors.toList());
            return patientIds;
        } else {
            List<Integer> patientIds = userRequestContext.getUserConsents().stream().map(phenotypicPartitions::get)
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getAllKeys(conceptPath).stream())
                .collect(Collectors.toList());
            return patientIds;
        }
    }

    public Optional<PhenoCube<?>> getCube(String path) {
        throw new RuntimeException("Not implemented");
    }

    public Set<String> getCachedKeys() {
        // todo: figure out a better solution for this
        return phenotypicPartitions.values().stream().findFirst().orElseThrow().getCachedKeys();
    }

    public Set<Integer> getPatientIds() {
        // todo: disallow this by default
        if (userRequestContext.getUserConsents().isEmpty()) {
            Set<Integer> patientIds = phenotypicPartitions.values().stream()
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getPatientIds().stream()).collect(Collectors.toSet());
            return patientIds;
        } else {
            Set<Integer> patientIds = userRequestContext.getUserConsents().stream().map(phenotypicPartitions::get)
                .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getPatientIds().stream()).collect(Collectors.toSet());
            return patientIds;
        }
    }

    public Set<String> getChildConceptPaths(String s) {
        throw new RuntimeException("Not implemented yet");
    }

    @Cacheable("PartitionedPhenotypicObservationStore.getMetaStore")
    public Map<String, SummaryColumnMeta> getMetaStore() {
        Map<String, SummaryColumnMeta> mergedColumnMeta = new HashMap<>();
        List<Map<String, ColumnMeta>> allPartitionMetaStores =
            phenotypicPartitions.values().stream().map(PhenotypicObservationStore::getMetaStore).collect(Collectors.toList());
        for (Map<String, ColumnMeta> metaStore : allPartitionMetaStores) {
            for (Map.Entry<String, ColumnMeta> stringColumnMetaEntry : metaStore.entrySet()) {
                SummaryColumnMeta summaryColumnMeta = mergedColumnMeta.get(stringColumnMetaEntry.getKey());
                if (summaryColumnMeta == null) {
                    summaryColumnMeta = new SummaryColumnMeta(stringColumnMetaEntry.getValue());
                } else {
                    summaryColumnMeta = summaryColumnMeta.merge(new SummaryColumnMeta(stringColumnMetaEntry.getValue()));
                }
                mergedColumnMeta.put(stringColumnMetaEntry.getKey(), summaryColumnMeta);
            }
        }
        return mergedColumnMeta;
    }


}
