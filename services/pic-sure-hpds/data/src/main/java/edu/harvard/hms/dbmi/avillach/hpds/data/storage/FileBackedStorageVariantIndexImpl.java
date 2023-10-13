package edu.harvard.hms.dbmi.avillach.hpds.data.storage;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;
import org.codehaus.jackson.type.TypeReference;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.Serializable;

public class FileBackedStorageVariantIndexImpl extends FileBackedJsonIndexStorage<String, Integer[]> implements Serializable {
    private static final long serialVersionUID = -893724459359928779L;

    public FileBackedStorageVariantIndexImpl(File storageFile) throws FileNotFoundException {
        super(storageFile);
    }

    private static final TypeReference<Integer[]> typeRef
            = new TypeReference<Integer[]>() {};

    @Override
    public TypeReference<Integer[]> getTypeReference() {
        return typeRef;
    }
}
