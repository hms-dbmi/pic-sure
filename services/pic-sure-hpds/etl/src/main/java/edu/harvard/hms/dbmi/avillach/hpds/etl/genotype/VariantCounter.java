package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.*;
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
			for(String contig : variantStore.getVariantMaskStorage().keySet()) {
				int[] countOfVariants = {0};
				FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> 
				currentChromosome = variantStore.getVariantMaskStorage().get(contig);
				currentChromosome.keys().parallelStream().forEach((offsetBucket)->{
					ConcurrentHashMap<String, VariantMasks> maskMap;
					try {
						maskMap = currentChromosome.get(offsetBucket);
						if(maskMap!=null) {
							countOfVariants[0]+=maskMap.size();							
						}
						
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
				System.out.println(contig + "," + countOfVariants[0]);
			}
		}
	}
}
