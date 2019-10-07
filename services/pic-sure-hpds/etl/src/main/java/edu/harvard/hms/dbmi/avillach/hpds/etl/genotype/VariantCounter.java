package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class VariantCounter {

	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		try(FileInputStream fis = new FileInputStream("/opt/local/hpds/all/variantStore.javabin");
				){
			VariantStore variantStore = (VariantStore) new ObjectInputStream(new GZIPInputStream(fis)).readObject();
			variantStore.open();
			for(int x = 1;x<23;x++) {
				int[] countOfVariants = {0};
				FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> 
				currentChromosome = variantStore.variantMaskStorage[x];
				currentChromosome.keys().parallelStream().forEach((offsetBucket)->{
					ConcurrentHashMap<String, VariantMasks> maskMap;
					try {
						maskMap = currentChromosome.get(offsetBucket);
						if(maskMap!=null) {
							countOfVariants[0]+=maskMap.size();							
						}
						
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				});
				System.out.println(x + "," + countOfVariants[0]);
			}
		}
	}
}
