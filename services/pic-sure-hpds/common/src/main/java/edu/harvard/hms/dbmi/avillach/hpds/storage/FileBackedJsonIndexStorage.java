package edu.harvard.hms.dbmi.avillach.hpds.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public abstract class FileBackedJsonIndexStorage <K, V extends Serializable> extends FileBackedByteIndexedStorage<K, V> {
    private static final long serialVersionUID = -1086729119489479152L;

    protected transient ObjectMapper objectMapper = new ObjectMapper();

    public FileBackedJsonIndexStorage(File storageFile) throws FileNotFoundException {
        super(null, null, storageFile);
    }

    protected ByteArrayOutputStream writeObject(V value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        objectMapper.writeValue(new GZIPOutputStream(out), value);
        return out;
    }

    protected V readObject(byte[] buffer) {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(buffer))) {
            return objectMapper.readValue(gzipInputStream, getTypeReference());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Required to populate the objectMapper on deserialization
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        objectMapper = new ObjectMapper();
    }

    public abstract TypeReference<V> getTypeReference();
}
