package edu.harvard.hms.dbmi.avillach.geno.data;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class VariantStore implements Serializable{
	private static final long serialVersionUID = -2291391302273445355L;
	public BigInteger emptyBitmask;
	private String[] patientIds;

	private int variantStorageSize;

	private String[] vcfHeaders = new String[24];

	public FileBackedByteIndexedStorage<Integer, HashMap<String, VariantMasks>>[] variantMaskStorage = new FileBackedByteIndexedStorage[24];

	public FileBackedByteIndexedStorage<Integer, HashMap<String, String>>[] variantInfoStorage = new FileBackedByteIndexedStorage[24];

	public FileBackedByteIndexedStorage<Integer, HashMap<String, Integer>>[] variantQualityStorage = new FileBackedByteIndexedStorage[24];

	public int[][] countVariants() {
		int[][] counts = new int[24][3];
		for(int x = 1;x<variantMaskStorage.length;x++) {
			FileBackedByteIndexedStorage<Integer, HashMap<String, VariantMasks>> storage = variantMaskStorage[x];
			final int final_x = x;
			storage.keys().stream().forEach((Integer key)->{
				try {
					counts[final_x][0] += storage.get(key).values().stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.heterozygousMask!=null?1:0;}));
					counts[final_x][1] += storage.get(key).values().stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.homozygousMask!=null?1:0;}));
					counts[final_x][2] += storage.get(key).values().stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.heterozygousMask!=null || masks.homozygousMask!=null?1:0;}));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		return counts;
	}

	public int[] countInfo() {
		int[] counts = new int[24];
		for(int x = 1;x<variantInfoStorage.length;x++) {
			FileBackedByteIndexedStorage<Integer, HashMap<String, String>> storage = variantInfoStorage[x];
			final int final_x = x;
			storage.keys().stream().forEach((Integer key)->{
				try {
					counts[final_x] += storage.get(key).size();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		return counts;
	}
	
	public int[] countQuality() {
		int[] counts = new int[24];
		for(int x = 1;x<variantQualityStorage.length;x++) {
			FileBackedByteIndexedStorage<Integer, HashMap<String, Integer>> storage = variantQualityStorage[x];
			final int final_x = x;
			storage.keys().stream().forEach((Integer key)->{
				try {
					counts[final_x] += storage.get(key).size();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		return counts;
	}
	
	public String[] getVCFHeaders() {
		return vcfHeaders;
	}

	public String[] getPatientIds() {
		return patientIds;
	}

	public VariantMasks getMasks(String variant) throws IOException {
		String[] segments = variant.split(",");
		int chrOffset = Integer.parseInt(segments[1]);
		int chrNumber = Integer.parseInt(segments[0]);
		return variantMaskStorage[chrNumber].get(chrOffset/10000).get(variant);
	}

	public VariantMasks getMasksForFilter(String variantFilter) throws IOException {
		String[] segments = variantFilter.split(",");
		int chrOffset = Integer.parseInt(segments[1].substring(0, segments[1].length()-4));
		int chrNumber = Integer.parseInt(segments[0]);
		if(variantFilter.contains("*")) {
			BigInteger[] heterozygousMask = {emptyBitmask};
			BigInteger[] homozygousMask = {emptyBitmask};
			variantMaskStorage[chrNumber].get(chrOffset).entrySet().stream().filter((Entry<String, VariantMasks> entry)->{
				return entry.getKey().matches(variantFilter);
			}).forEach((Entry<String, VariantMasks> entry)->{
				heterozygousMask[0] = heterozygousMask[0].or(entry.getValue().heterozygousMask);
				homozygousMask[0] = homozygousMask[0].or(entry.getValue().homozygousMask);
			});
			VariantMasks variantMasks = new VariantMasks();
			variantMasks.heterozygousMask = heterozygousMask[0];
			variantMasks.homozygousMask = homozygousMask[0];
			return variantMasks;
		}
		return variantMaskStorage[chrNumber].get(chrOffset/10000).get(variantFilter);
	}

	public Integer getQuality(String variant) throws IOException {
		String[] segments = variant.split(",");
		int chrOffset = Integer.parseInt(segments[1]);
		int chrNumber = Integer.parseInt(segments[0]);
		return variantQualityStorage[chrNumber].get(chrOffset/10000).get(variant);
	}

	public String getInfo(String variant) throws IOException {
		String[] segments = variant.split(",");
		int chrOffset = Integer.parseInt(segments[1]);
		int chrNumber = Integer.parseInt(segments[0]);
		return variantInfoStorage[chrNumber].get(chrOffset/10000).get(variant);
	}
	
	public String[] getHeaders() {
		return vcfHeaders;
	}

	public void open() {
		Arrays.stream(variantMaskStorage).forEach((fbbis->{
			if(fbbis!=null) {
				try {
					fbbis.open();
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}));
		Arrays.stream(variantInfoStorage).forEach((fbbis->{
			if(fbbis!=null) {
				try {
					fbbis.open();
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}));
		Arrays.stream(variantQualityStorage).forEach((fbbis->{
			if(fbbis!=null) {
				try {
					fbbis.open();
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}));
	}

	public VariantStore(File sourceDir, File storageDir) throws IOException {
		for(int x = 0;x<24;x++) {
			variantMaskStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(storageDir, "chr" + x + "masks.bin"));
			variantInfoStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(storageDir, "chr" + x + "info.bin"));
			variantQualityStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(storageDir, "chr" + x + "quality.bin"));
		}

		FileFilter filter = (File file) -> {
			return (file.getName().endsWith("vcf") || file.getName().endsWith("vcf.bgz")) && !file.getName().contains("chrX");
		};
		
		Arrays.asList(sourceDir.listFiles(filter)).stream().forEach(new VCFConsumer(sourceDir, this));
	}

	public void setPatientIds(String[] patientIds) {
		this.patientIds = patientIds;
	}

	public int getVariantStorageSize() {
		return variantStorageSize;
	}

	public void setVariantStorageSize(int variantStorageSize) {
		this.variantStorageSize = variantStorageSize;
	}
	
}
