package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.common.collect.RangeSet;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class VariantStore implements Serializable{
	public static final int BUCKET_SIZE = 1000;
	private static final long serialVersionUID = -6970128712587609414L;
	public BigInteger emptyBitmask;
	private String[] patientIds;

	private Integer variantStorageSize;

	private String[] vcfHeaders = new String[24];
	
	public FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>[] variantMaskStorage = new FileBackedByteIndexedStorage[24];

	public ArrayList<String> listVariants() {
		ArrayList<String> allVariants = new ArrayList<>();
		for(int x = 1;x<variantMaskStorage.length-1;x++) {
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> storage = variantMaskStorage[x];
			final int final_x = x;
			storage
			.keys()
			.stream()
			.forEach((Integer key)->{
				try {
					for(String variant : storage.get(key).keySet()) {
						allVariants.add(variant);
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		return allVariants;
	}
	
	public int[][] countVariants() {
		int[][] counts = new int[24][5];
		for(int x = 1;x<variantMaskStorage.length-1;x++) {
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> storage = variantMaskStorage[x];
			final int final_x = x;
			storage
			.keys()
			.stream()
			.forEach((Integer key)->{
				try {
					Collection<VariantMasks> values = storage.get(key).values();
					counts[final_x][0] += values.stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.heterozygousMask!=null?1:0;}));
					counts[final_x][1] += values.stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.homozygousMask!=null?1:0;}));
					counts[final_x][2] += values.stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.heterozygousNoCallMask!=null?1:0;}));
					counts[final_x][3] += values.stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.homozygousNoCallMask!=null?1:0;}));
					counts[final_x][4] += values.stream().collect(Collectors.summingInt((VariantMasks masks)->{return masks.heterozygousMask!=null || masks.homozygousMask!=null || masks.heterozygousNoCallMask!=null || masks.homozygousNoCallMask!=null?1:0;}));
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

	private ConcurrentHashMap<String, VariantMasks> lastSetOfVariants;
	private int lastChr;
	private int lastChunkOffset;
	
	public VariantMasks getMasks(String variant) throws IOException {
		String[] segments = variant.split(",");
		if(segments.length<2) {
			System.out.println("Less than 2 segments found in this variant : " + variant);
		}
		int chrOffset = Integer.parseInt(segments[1])/ BUCKET_SIZE;
		int chrNumber = Integer.parseInt(segments[0]);
		if(lastSetOfVariants != null && chrNumber == lastChr && chrOffset == lastChunkOffset) {
			// TODO : This is a temporary efficiency hack, NOT THREADSAFE!!!
		} else {
			lastSetOfVariants = variantMaskStorage[chrNumber].get(chrOffset);	
			lastChr = chrNumber;
			lastChunkOffset = chrOffset;
		}
		return lastSetOfVariants == null ? null : lastSetOfVariants.get(variant);
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
	}

	public VariantStore(){

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

	public List<VariantMasks> getMasksForRangesOfChromosome(Integer chromosomeForGene, List<Integer> offsetsForGene, RangeSet<Integer> rangeSetsForGene) throws IOException {
		FileBackedByteIndexedStorage masksForChromosome = variantMaskStorage[chromosomeForGene];
		Set<Integer> bucketsForGene = offsetsForGene.stream().map((offset)->{ return offset/BUCKET_SIZE; }).collect(Collectors.toSet());
		List<VariantMasks> masks = new ArrayList<VariantMasks>();
		for(Integer bucket : bucketsForGene) {
			Map<String, VariantMasks> variantMaskBucket = (Map<String, VariantMasks>) masksForChromosome.get(bucket);
			variantMaskBucket.keySet().stream().filter((String spec)->{
				int offsetForVariant = Integer.parseInt(spec.split(",")[1]);
				return rangeSetsForGene.contains(offsetForVariant);
			}).forEach((spec)->{
				System.out.println(spec);
				masks.add(variantMaskBucket.get(spec));
			});
		}
		return masks;
	}

	public BigInteger emptyBitmask() {
		if(emptyBitmask == null || emptyBitmask.testBit(emptyBitmask.bitLength()/2)) {
			String emptyVariantMask = "";
			for(String patientId : patientIds) {
				emptyVariantMask = emptyVariantMask + "0";
			}
			emptyBitmask = new BigInteger("11" + emptyVariantMask + "11", 2);
		}
		return emptyBitmask;
	}

}
