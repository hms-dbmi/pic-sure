package edu.harvard.hms.dbmi.avillach.hpds.storage;

import java.io.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public abstract class FileBackedByteIndexedStorage <K, V extends Serializable> implements Serializable {
	private static final long serialVersionUID = -7297090745384302635L;
	protected transient RandomAccessFile storage;
	protected ConcurrentHashMap<K, Long[]> index;
	protected File storageFile;
	protected boolean completed = false;


	public FileBackedByteIndexedStorage(Class<K> keyClass, Class<V> valueClass, File storageFile) throws FileNotFoundException {
		this.index = new ConcurrentHashMap<K, Long[]>();
		this.storageFile = storageFile;
		this.storage = new RandomAccessFile(this.storageFile, "rw");
	}

	public void updateStorageDirectory(File storageDirectory) {
		if (!storageDirectory.isDirectory()) {
			throw new IllegalArgumentException("storageDirectory is not a directory");
		}
		String currentStoreageFilename = storageFile.getName();
		storageFile = new File(storageDirectory, currentStoreageFilename);
	}

	public Set<K> keys(){
		return index.keySet();
	}

	public void put(K key, V value) {
		if(completed) {
			throw new RuntimeException("A completed FileBackedByteIndexedStorage cannot be modified.");
		}
		Long[] recordIndex;
		try (ByteArrayOutputStream out = writeObject(value)) {
			recordIndex = new Long[2];
			synchronized (storage) {
				storage.seek(storage.length());
				recordIndex[0] = storage.getFilePointer();
				storage.write(out.toByteArray());
				recordIndex[1] = storage.getFilePointer() - recordIndex[0];
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		index.put(key, recordIndex);
	}

	public void load(Iterable<V> values, Function<V, K> mapper) {
		//make sure we start fresh
		if(this.storageFile.exists()) {
			this.storageFile.delete();
		}
		try (RandomAccessFile storage = new RandomAccessFile(storageFile, "rw");) {
			this.storage = storage;
			for(V value : values) {
				put(mapper.apply(value), value);
			}
			complete();
		} catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

	public void open() {
        try {
            this.storage = new RandomAccessFile(this.storageFile, "rwd");
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

	public void complete() {
		this.completed = true;
	}

	public V get(K key) {
		try {
			// todo: make this class immutable and remove this lock/check altogether
			synchronized(this) {
				if(this.storage==null) {
					this.open();
				}
			}
			Long[] offsetsInStorage = index.get(key);
			if(offsetsInStorage != null) {
				Long offsetInStorage = index.get(key)[0];
				int offsetLength = index.get(key)[1].intValue();
				if(offsetInStorage != null && offsetLength>0) {
					byte[] buffer = new byte[offsetLength];
					synchronized(storage) {
						storage.seek(offsetInStorage);
						storage.readFully(buffer);
					}
					V readObject = readObject(buffer);
					return readObject;
				}else {
					return null;
				}
			} else {
				return null;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected abstract V readObject(byte[] buffer);

	protected abstract ByteArrayOutputStream writeObject(V value) throws IOException;

	public V getOrELse(K key, V defaultValue) {
		V result = get(key);
		return result == null ? defaultValue : result;
	}

}
