package edu.harvard.hms.dbmi.avillach.hpds.storage;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class FileBackedJavaIndexedStorage <K, V extends Serializable> extends FileBackedByteIndexedStorage<K, V> {
    public FileBackedJavaIndexedStorage(Class<K> keyClass, Class<V> valueClass, File storageFile) throws FileNotFoundException {
        super(keyClass, valueClass, storageFile);
    }

    protected ByteArrayOutputStream writeObject(V value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(out));
        oos.writeObject(value);
        oos.flush();
        oos.close();
        return out;
    }

    @Override
    protected V readObject(byte[] buffer) {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(buffer)));) {
            V readObject = (V) in.readObject();
            return readObject;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
