package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Component
public class PhenotypeMetaStore {

    private static final Logger log = LoggerFactory.getLogger(AbstractProcessor.class);

    // Todo: Test using hash map/sets here
    private TreeMap<String, ColumnMeta> metaStore;

    private TreeSet<Integer> patientIds;
    private String columnMetaFile;

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

    public PhenotypeMetaStore() {
        String hpdsDataDirectory = System.getProperty("HPDS_DATA_DIRECTORY", "/opt/local/hpds/");
        columnMetaFile = hpdsDataDirectory + "columnMeta.javabin";
        try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(columnMetaFile)));){
            TreeMap<String, ColumnMeta> _metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
            TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
            for(Map.Entry<String,ColumnMeta> entry : _metastore.entrySet()) {
                metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
            }
            metaStore = metastoreScrubbed;
            patientIds = (TreeSet<Integer>) objectInputStream.readObject();
            objectInputStream.close();
        } catch (IOException | ClassNotFoundException e) {
            log.warn("************************************************");
            log.warn("Could not load metastore", e);
            log.warn("If you meant to include phenotype data of any kind, please check that the file " + columnMetaFile + " exists and is readable by the service.");
            log.warn("************************************************");
            metaStore = new TreeMap<String, ColumnMeta>();
            patientIds = new TreeSet<Integer>();
        }
    }

    public PhenotypeMetaStore(TreeMap<String, ColumnMeta> metaStore, TreeSet<Integer> patientIds) {
        this.metaStore = metaStore;
        this.patientIds = patientIds;
    }
}
