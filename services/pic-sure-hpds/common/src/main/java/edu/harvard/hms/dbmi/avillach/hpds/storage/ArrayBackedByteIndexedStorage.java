package edu.harvard.hms.dbmi.avillach.hpds.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

import org.apache.commons.io.output.ByteArrayOutputStream;

public class ArrayBackedByteIndexedStorage <K, V> implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1559766733105772618L;
	private HashMap<K, int[]> index;
	private byte[] storage;
	private boolean completed = false;
	private long loadingPointer = 0;
	private transient ByteArrayOutputStream loadingbaos = new ByteArrayOutputStream();
	
	public ArrayBackedByteIndexedStorage(Class<K> keyClass, Class<V> valueClass) {
		this.index = new HashMap<K, int[]>();
	}

	public void put(K key, V value) throws IOException {
		if(completed) {
			throw new RuntimeException("Cannot add new records to completed storage");
		}
		int[] recordIndex = new int[2];
		recordIndex[0] = (int) loadingPointer;
		recordIndex[1] = store(value);
		loadingPointer += recordIndex[1];
		index.put(key, recordIndex);
	}

	private int store(V value) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(out);
		oos.writeObject(value);
		oos.flush();
		oos.close();
		byte[] bytes = out.toByteArray();
		loadingbaos.write(bytes);
		return bytes.length;
	}

	public V get(K key) throws IOException {
		int[] offsetsInStorage = index.get(key);
		if(offsetsInStorage != null) {
			byte[] buffer = new byte[offsetsInStorage[1]];
			System.arraycopy(storage, offsetsInStorage[0], buffer, 0, buffer.length);
			ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer));
			try {
				V readObject = (V) in.readObject();
				return readObject;
			} catch (ClassNotFoundException e) {
				throw new RuntimeException("This should never happen.");
			} finally {
				in.close();
			}
		} else {
			return null;
		}
	}

	public void put(String specNotation, int qual) {
		
	}
}
