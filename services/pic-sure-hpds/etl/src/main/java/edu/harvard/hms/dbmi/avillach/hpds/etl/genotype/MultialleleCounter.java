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

public class MultialleleCounter {

	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		try(FileInputStream fis = new FileInputStream("/opt/local/hpds/all/variantStore.javabin");
				){
			VariantStore variantStore = (VariantStore) new ObjectInputStream(new GZIPInputStream(fis)).readObject();
			variantStore.open();
			for(String contig : variantStore.variantMaskStorage.keySet()) {
				System.out.println("Starting contig : " + contig);
				FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> 
				currentChromosome = variantStore.variantMaskStorage.get(contig);
				currentChromosome.keys().parallelStream().forEach((offsetBucket)->{
					System.out.println("Starting bucket : " + offsetBucket);
					ConcurrentHashMap<String, VariantMasks> maskMap;
					try {
						maskMap = currentChromosome.get(offsetBucket);

						TreeSet<VariantSpec> variantsSortedByOffset = new TreeSet<VariantSpec>();
						for(String variant : maskMap.keySet()) {
							variantsSortedByOffset.add(new VariantSpec(variant));
						}
						ArrayList<VariantSpec> variantsSortedByOffsetList = new ArrayList(variantsSortedByOffset);
						for(int y = 1; y<variantsSortedByOffsetList.size();y++) {
							if(variantsSortedByOffsetList.get(y).metadata.offset.equals(variantsSortedByOffsetList.get(y-1).metadata.offset)) {
								try {
									System.out.println("Matching offsets : " + variantsSortedByOffsetList.get(y-1).specNotation() + " : " + variantsSortedByOffsetList.get(y).specNotation() + ":" + maskMap.get(variantsSortedByOffsetList.get(y-1).specNotation()).heterozygousMask.toString(2) + ":" + ":" + maskMap.get(variantsSortedByOffsetList.get(y).specNotation()).heterozygousMask.toString(2));
								}catch(NullPointerException e) {
									System.out.println("Matching offsets : " + variantsSortedByOffsetList.get(y-1).specNotation() + " : " + variantsSortedByOffsetList.get(y).specNotation());
								}
							}
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					System.out.println("Completed bucket : " + offsetBucket);
				});
			}
		}
	}
}
