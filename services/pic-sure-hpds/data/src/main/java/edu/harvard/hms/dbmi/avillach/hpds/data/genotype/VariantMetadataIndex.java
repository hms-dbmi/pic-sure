package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

/**
 * To be implemented as part of ALS-112
 * 
 */
public class VariantMetadataIndex implements Serializable {

	private static final long serialVersionUID = 5917054606643971537L;

	public static String storageFile = "/opt/local/hpds/all/VariantMetadataStorage.bin";
	public static String binFile = "/opt/local/hpds/all/VariantMetadata.javabin";
	FileBackedByteIndexedStorage<String, String[]> fbbis;   
	
	public VariantMetadataIndex() throws IOException { 
		fbbis = new FileBackedByteIndexedStorage<>(String.class, String[].class, new File(storageFile)); 
	} 
	
	public void initializeRead() throws FileNotFoundException, IOException, ClassNotFoundException {
		ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(new File(binFile))));
		fbbis = (FileBackedByteIndexedStorage<String, String[]>) in.readObject(); 
	}

	public String[] findBySingleVariantSpec(String string) throws Exception {
		return fbbis.get(string);
	}

	public Map<String, String[]> findByMultipleVariantSpec(List<String> varientSpecList) throws Exception {
		Map<String, String[]> result = varientSpecList.stream().collect(HashMap::new, (m, v) -> {
			try {
				m.put(v, this.findBySingleVariantSpec(v));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, HashMap::putAll);
		return result;
	}

	public void put(String specNotation, String[] array) throws IOException {
		fbbis.put(specNotation, array);
	}

	public void complete() {
		fbbis.complete();
	}

	public FileBackedByteIndexedStorage<String, String[]> getFbbis() {
		return fbbis;
	}
}