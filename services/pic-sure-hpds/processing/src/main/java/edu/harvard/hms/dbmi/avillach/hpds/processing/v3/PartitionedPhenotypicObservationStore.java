package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PartitionedPhenotypicObservationStore {

    private static final Logger log = LoggerFactory.getLogger(PartitionedPhenotypicObservationStore.class);

    private final UserRequestContext userRequestContext;

    private final Map<String, PhenotypicObservationStore> phenotypicPartitions;

    private final Map<String, SummaryColumnMeta> allPartitionMetaStore;

    private final boolean requireAuthorizationFilter;

    @Autowired
    public PartitionedPhenotypicObservationStore(
        UserRequestContext userRequestContext, @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory,
        @Value("${hpds.requireAuthorizationFilter:true}") boolean requireAuthorizationFilter
    ) {
        this.userRequestContext = userRequestContext;
        this.requireAuthorizationFilter = requireAuthorizationFilter;

        try (Stream<Path> stream = Files.list(Path.of(hpdsDataDirectory))) {
            List<Path> subdirectories = stream.filter(Files::isDirectory)
                .filter(subdirectory -> !subdirectory.equals(Path.of(hpdsDataDirectory))).collect(Collectors.toList());

            Map<String, PhenotypicObservationStore> phenotypicPartitions = new HashMap<>();

            for (Path subdirectory : subdirectories) {
                String partitionName = subdirectory.getFileName().toString();
                PhenotypeMetaStore phenotypeMetaStore = new PhenotypeMetaStore(subdirectory.toString());
                PhenotypicObservationStore phenotypicObservationStore =
                    new PhenotypicObservationStore(phenotypeMetaStore, subdirectory.toString(), 1000);
                phenotypicPartitions.put(partitionName, phenotypicObservationStore);
            }

            this.phenotypicPartitions = Map.copyOf(phenotypicPartitions);
            this.allPartitionMetaStore = createAllPartitionMetaStore();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Integer> getKeysForRange(String conceptPath, Double min, Double max) {
        return aggregateForPartition(phenotypicObservationStore -> phenotypicObservationStore.getKeysForRange(conceptPath, min, max))
            .collect(Collectors.toSet());
    }

    public Set<Integer> getKeysForValues(String conceptPath, Collection<String> values) {
        return aggregateForPartition((phenotypicObservationStore -> phenotypicObservationStore.getKeysForValues(conceptPath, values)))
            .collect(Collectors.toSet());
    }

    public List<Integer> getAllKeys(String conceptPath) {
        return aggregateForPartition(phenotypicObservationStore -> phenotypicObservationStore.getAllKeys(conceptPath))
            .collect(Collectors.toList());
    }

    public Optional<PhenoCube<?>> getCube(String path) {
        Set<PhenoCube<?>> phenoCubes = getPartitionsForUser()
            .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getCube(path).stream()).collect(Collectors.toSet());
        PhenoCube<?> result = phenoCubes.stream().reduce((phenoCube, phenoCube2) -> {
            if (phenoCube.vType.equals(String.class)) {
                return ((PhenoCube<String>) phenoCube).merge((PhenoCube<String>) phenoCube2);
            }
            return ((PhenoCube<Double>) phenoCube).merge((PhenoCube<Double>) phenoCube2);
        }).get();
        return Optional.ofNullable(result);
    }

    public Set<String> getCachedKeys() {
        // todo: figure out a better solution for this
        return phenotypicPartitions.values().stream().findFirst().orElseThrow().getCachedKeys();
    }

    public Set<Integer> getPatientIds() {
        return aggregateForPartition(PhenotypicObservationStore::getPatientIds).collect(Collectors.toSet());
    }

    @Cacheable("PartitionedPhenotypicObservationStore.getMetaStore")
    public Map<String, SummaryColumnMeta> getMetaStore() {
        return allPartitionMetaStore;
    }

    private Map<String, SummaryColumnMeta> createAllPartitionMetaStore() {
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
        return Map.copyOf(mergedColumnMeta);
    }

    private <T> Stream<T> aggregateForPartition(Function<PhenotypicObservationStore, Collection<T>> partitionFunction) {
        return getPartitionsForUser().map(partitionFunction).flatMap(Collection::stream);
    }

    private @NonNull Stream<PhenotypicObservationStore> getPartitionsForUser() {
        if (userRequestContext.getUserConsents().isEmpty()) {
            if (requireAuthorizationFilter) {
                throw new MissingConsentsException(
                    "User consents must be specified. To allow users access to all data set hpds.requireAuthorizationFilter=false"
                );
            }
            return phenotypicPartitions.values().stream();
        } else {
            return userRequestContext.getUserConsents().stream().map(phenotypicPartitions::get).filter(Objects::nonNull);
        }
    }


}
