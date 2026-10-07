package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
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

    private static final String OBSERVATION_STORE_FILE = "allObservationsStore.javabin";

    /**
     * Partition name used for a legacy, unpartitioned data directory. Consents are never matched against it, so it cannot collide with a
     * real consent.
     */
    private static final String UNPARTITIONED_NAME = "__unpartitioned__";

    private final Map<String, PhenotypicObservationStore> phenotypicPartitions;

    private final Map<String, SummaryColumnMeta> allPartitionMetaStore;

    private final boolean requireAuthorizationFilter;

    /**
     * True when the data directory holds a single store rather than one subdirectory per consent. Such a directory cannot be scoped by
     * consent, so it is only allowed when authorization filtering is disabled.
     */
    private final boolean unpartitioned;

    @Autowired
    public PartitionedPhenotypicObservationStore(
        @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory,
        @Value("${hpds.requireAuthorizationFilter:true}") boolean requireAuthorizationFilter
    ) {
        this.requireAuthorizationFilter = requireAuthorizationFilter;

        Path dataDirectory = Path.of(hpdsDataDirectory);
        Map<String, PhenotypicObservationStore> partitions = new HashMap<>();

        if (Files.exists(dataDirectory.resolve(OBSERVATION_STORE_FILE))) {
            if (requireAuthorizationFilter) {
                throw new IllegalStateException(
                    "Found unpartitioned " + OBSERVATION_STORE_FILE + " in " + hpdsDataDirectory
                        + ", which cannot be scoped by consent. Re-run the ETL to partition the data by consent, or set"
                        + " hpds.requireAuthorizationFilter=false"
                );
            }
            log.info("Found {} in {}, loading it as a single unpartitioned partition", OBSERVATION_STORE_FILE, hpdsDataDirectory);
            this.unpartitioned = true;
            partitions.put(UNPARTITIONED_NAME, storeFor(dataDirectory));
        } else {
            this.unpartitioned = false;
            try (Stream<Path> stream = Files.list(dataDirectory)) {
                List<Path> subdirectories = stream.filter(Files::isDirectory).collect(Collectors.toList());
                for (Path subdirectory : subdirectories) {
                    partitions.put(subdirectory.getFileName().toString(), storeFor(subdirectory));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info("Loaded phenotypic partitions: {}", partitions.keySet());
        }

        this.phenotypicPartitions = Map.copyOf(partitions);
        this.allPartitionMetaStore = createAllPartitionMetaStore();
    }

    private static PhenotypicObservationStore storeFor(Path directory) {
        return new PhenotypicObservationStore(new PhenotypeMetaStore(directory.toString()), directory.toString(), 1000);
    }

    public Set<Integer> getKeysForRange(String conceptPath, Double min, Double max, Set<String> consents) {
        return aggregateForPartition(
            consents, phenotypicObservationStore -> phenotypicObservationStore.getKeysForRange(conceptPath, min, max)
        ).collect(Collectors.toSet());
    }

    public Set<Integer> getKeysForValues(String conceptPath, Collection<String> values, Set<String> consents) {
        return aggregateForPartition(
            consents, (phenotypicObservationStore -> phenotypicObservationStore.getKeysForValues(conceptPath, values))
        ).collect(Collectors.toSet());
    }

    public Set<Integer> getAllKeys(String conceptPath, Set<String> consents) {
        return aggregateForPartition(consents, phenotypicObservationStore -> phenotypicObservationStore.getAllKeys(conceptPath))
            .collect(Collectors.toSet());
    }

    /**
     * The cube for {@code path}, merged across the partitions {@code consents} grants, or empty when none of them holds one. That is an
     * ordinary outcome rather than an error: {@link #getMetaStore()} unions the column metadata of every partition, so a caller can
     * legitimately name a concept that lives only outside their own, and consents for studies this node does not host read nothing here.
     */
    public Optional<PhenoCube<?>> getCube(String path, Set<String> consents) {
        Set<PhenoCube<?>> phenoCubes = partitionsFor(consents)
            .flatMap(phenotypicObservationStore -> phenotypicObservationStore.getCube(path).stream()).collect(Collectors.toSet());
        return phenoCubes.stream().reduce((phenoCube, phenoCube2) -> {
            if (phenoCube.vType.equals(String.class)) {
                return ((PhenoCube<String>) phenoCube).merge((PhenoCube<String>) phenoCube2);
            }
            return ((PhenoCube<Double>) phenoCube).merge((PhenoCube<Double>) phenoCube2);
        });
    }

    public Set<String> getCachedKeys() {
        // todo: figure out a better solution for this
        return phenotypicPartitions.values().stream().findFirst().orElseThrow().getCachedKeys();
    }

    public Set<Integer> getPatientIds(Set<String> consents) {
        return aggregateForPartition(consents, PhenotypicObservationStore::getPatientIds).collect(Collectors.toSet());
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

    private <T> Stream<T> aggregateForPartition(
        Set<String> consents, Function<PhenotypicObservationStore, Collection<T>> partitionFunction
    ) {
        return partitionsFor(consents).map(partitionFunction).flatMap(Collection::stream);
    }

    private @NonNull Stream<PhenotypicObservationStore> partitionsFor(Set<String> consents) {
        if (consents == null || consents.isEmpty()) {
            if (requireAuthorizationFilter) {
                throw new MissingConsentsException(
                    "User consents must be specified. To allow users access to all data set hpds.requireAuthorizationFilter=false"
                );
            }
            return phenotypicPartitions.values().stream();
        }

        if (unpartitioned) {
            // Legacy, unpartitioned data is backwards compatible when authorization filtering is disabled
            return phenotypicPartitions.values().stream();
        }

        return consents.stream().map(consent -> {
            PhenotypicObservationStore partition = phenotypicPartitions.get(consent);
            if (partition == null) {
                log.debug("No partition found for consent {}", consent);
            }
            return partition;
        }).filter(Objects::nonNull);
    }


}
