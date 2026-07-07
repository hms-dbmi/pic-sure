package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Component
public class PhenotypeMetaStore {

    private static final Logger log = LoggerFactory.getLogger(PhenotypeMetaStore.class);


    // Todo: Test using hash map/sets here
    private TreeMap<String, ColumnMeta> metaStore;

    private TreeSet<Integer> patientIds;

    private final LoadingCache<String, Set<String>> childConceptCache;

    public TreeMap<String, ColumnMeta> getMetaStore() {
        return metaStore;
    }

    public TreeSet<Integer> getPatientIds() {
        return patientIds;
    }

    public Set<String> getColumnNames() {
        return metaStore.keySet();
    }

    public ColumnMeta getColumnMeta(String columnName) {
        return metaStore.get(columnName);
    }

    public Set<String> loadChildConceptPaths(String conceptPath) {
        return metaStore.keySet().stream().filter(column -> column.startsWith(conceptPath)).collect(Collectors.toSet());
    }

    public Set<String> getChildConceptPaths(String conceptPath) {
        try {
            return childConceptCache.get(conceptPath);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    @SuppressWarnings("unchecked")
    public PhenotypeMetaStore(
        @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String hpdsDataDirectory,
        @Value("${CHILD_CONCEPT_CACHE_SIZE:500}") int childConceptCacheSize
    ) {
        String columnMetaFile = hpdsDataDirectory + "columnMeta.javabin";
        try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(columnMetaFile)))) {
            TreeMap<String, ColumnMeta> _metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
            TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<>();
            for (Map.Entry<String, ColumnMeta> entry : _metastore.entrySet()) {
                metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd", ""), entry.getValue());
            }
            metaStore = metastoreScrubbed;
            patientIds = (TreeSet<Integer>) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            log.warn("************************************************");
            log.warn("Could not load metastore", e);
            log.warn(
                "If you meant to include phenotype data of any kind, please check that the file " + columnMetaFile
                    + " exists and is readable by the service."
            );
            log.warn("************************************************");
            metaStore = new TreeMap<>();
            patientIds = new TreeSet<>();
        }
        childConceptCache = CacheBuilder.newBuilder().maximumSize(childConceptCacheSize).build(new CacheLoader<>() {
            @Override
            public Set<String> load(String key) {
                return loadChildConceptPaths(key);
            }
        });
    }

    public PhenotypeMetaStore(
        TreeMap<String, ColumnMeta> metaStore, TreeSet<Integer> patientIds,
        @Value("${CHILD_CONCEPT_CACHE_SIZE:500}") int childConceptCacheSize
    ) {
        this.metaStore = metaStore;
        this.patientIds = patientIds;

        this.childConceptCache = CacheBuilder.newBuilder().maximumSize(childConceptCacheSize).build(new CacheLoader<>() {
            @Override
            public Set<String> load(String key) {
                return loadChildConceptPaths(key);
            }
        });
    }
}
