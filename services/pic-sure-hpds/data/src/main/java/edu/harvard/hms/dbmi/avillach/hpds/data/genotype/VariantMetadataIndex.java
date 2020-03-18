package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

/**
 * To be implemented as part of ALS-112
 * 
 */
public class VariantMetadataIndex implements Serializable {
	private static final long serialVersionUID = 5917054606643971537L;
	private static Logger log = Logger.getLogger(VariantMetadataIndex.class); 
	private FileBackedByteIndexedStorage<String, String[]> fbbis;   
	
	public VariantMetadataIndex(String storageFile) throws IOException { 
		fbbis = new FileBackedByteIndexedStorage<>(String.class, String[].class, new File(storageFile));   
	}  
	
	public VariantMetadataIndex() { 
	}

	public void initializeRead(String binFile) throws FileNotFoundException, IOException, ClassNotFoundException {
		try(ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(new File(binFile))))){
			fbbis  = ((VariantMetadataIndex) in.readObject()).fbbis;
		}
	}

	public String[] findBySingleVariantSpec(String variantSpec) {
		try {
			String[] value = fbbis.get(variantSpec);
			return value != null  ? value :  new String[0];
		} catch (IOException e) {
			log.warn("IOException caught looking up variantSpec : " + variantSpec, e);
			return new String[0];
		}
	}

	public Map<String, String[]> findByMultipleVariantSpec(List<String> varientSpecList) {
		return varientSpecList.stream().collect(Collectors.toMap(
				variant->{return variant;},
				variant->{return findBySingleVariantSpec(variant);}
				));
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