package edu.harvard.hms.dbmi.avillach.hpds.data.storage;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;
import org.codehaus.jackson.type.TypeReference;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;

public class FileBackedStorageVariantMasksImpl extends FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>> implements Serializable {
    private static final long serialVersionUID = -1086729119489479152L;

    public FileBackedStorageVariantMasksImpl(File storageFile) throws FileNotFoundException {
        super(storageFile);
    }
    private static final TypeReference<ConcurrentHashMap<String, VariantMasks>> typeRef
            = new TypeReference<ConcurrentHashMap<String, VariantMasks>>() {};

    @Override
    public TypeReference<ConcurrentHashMap<String, VariantMasks>> getTypeReference() {
        return typeRef;
    }
}
