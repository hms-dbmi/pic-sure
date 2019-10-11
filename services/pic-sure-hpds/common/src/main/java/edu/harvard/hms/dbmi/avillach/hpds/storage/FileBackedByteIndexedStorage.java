package edu.harvard.hms.dbmi.avillach.hpds.storage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.output.ByteArrayOutputStream;

public class FileBackedByteIndexedStorage <K, V extends Serializable> implements Serializable {
	private static final long serialVersionUID = -7297090745384302635L;
	private transient RandomAccessFile storage;
	private ConcurrentHashMap<K, Long[]> index;
	private File storageFile;
	private boolean completed = false;
	private Long maxStorageSize;

	public FileBackedByteIndexedStorage(Class<K> keyClass, Class<V> valueClass, File storageFile) throws FileNotFoundException {
		this.index = new ConcurrentHashMap<K, Long[]>();
		this.storageFile = storageFile;
		this.storage = new RandomAccessFile(this.storageFile, "rw");
	}

	public Set<K> keys(){
		return index.keySet();
	}

	public void put(K key, V value) throws IOException {
		if(completed) {
			throw new RuntimeException("A completed FileBackedByteIndexedStorage cannot be modified.");
		}
		Long[] recordIndex = new Long[2];
		recordIndex[0] = storage.getFilePointer();
		store(value);
		recordIndex[1] = storage.getFilePointer() - recordIndex[0];
		maxStorageSize = recordIndex[1];
		index.put(key, recordIndex);
	}

	public void load(Iterable<V> values, Function<V, K> mapper) throws IOException {
		boolean deleted = this.storageFile.exists() ? this.storageFile.delete() : false;
		this.storage = new RandomAccessFile(storageFile, "rw");
		for(V value : values) {
			put(mapper.apply(value), value);
		}
		this.storage.close();
		complete();
	}

	public void open() throws FileNotFoundException {
		this.storage = new RandomAccessFile(this.storageFile, "rwd");
	}

	public void complete() {
		this.completed = true;
	}

	private void store(V value) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(out));
		oos.writeObject(value);
		oos.flush();
		oos.close();
		synchronized(storage) {
			storage.write(out.toByteArray());			
		}
	}

	public V get(K key) throws IOException {
		if(this.storage==null) {
			synchronized(storage) {
				this.open();
			}
		}
		Long[] offsetsInStorage = index.get(key);
		if(offsetsInStorage != null) {
			Long offsetInStorage = index.get(key)[0];
			int offsetLength = index.get(key)[1].intValue();
			if(offsetInStorage > 0 && offsetLength>0) {
				byte[] buffer = new byte[offsetLength];
				synchronized(storage) {
					storage.seek(offsetInStorage);
					storage.readFully(buffer);					
				}
				ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(buffer)));

				try {
					V readObject = (V) in.readObject();
					return readObject;
				} catch (ClassNotFoundException e) {
					throw new RuntimeException("This should never happen.");
				} finally {
					in.close();
				}
			}else {
				return null;
			}
		} else {
			return null;
		}
	}
}
