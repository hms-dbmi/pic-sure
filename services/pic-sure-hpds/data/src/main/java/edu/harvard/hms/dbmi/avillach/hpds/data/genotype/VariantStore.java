package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.errorprone.annotations.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.google.common.collect.RangeSet;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class VariantStore implements Serializable {
	private static final long serialVersionUID = -6970128712587609414L;
	private static Logger log = LoggerFactory.getLogger(VariantStore.class);
	public static final int BUCKET_SIZE = 1000;

	private BigInteger emptyBitmask;
	private String[] patientIds;

	private Integer variantStorageSize;

	private String[] vcfHeaders = new String[24];

	private TreeMap<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> variantMaskStorage = new TreeMap<>();

	public TreeMap<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> getVariantMaskStorage() {
		return variantMaskStorage;
	}

	public void setVariantMaskStorage(TreeMap<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> variantMaskStorage) {
		this.variantMaskStorage = variantMaskStorage;
	}

	public static VariantStore deserializeInstance(String genomicDataDirectory) throws IOException, ClassNotFoundException, InterruptedException {
		if(new File(genomicDataDirectory + "variantStore.javabin").exists()) {
			ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(genomicDataDirectory + "variantStore.javabin")));
			VariantStore variantStore = (VariantStore) ois.readObject();
			ois.close();
			variantStore.open();
			return variantStore;
		} else {
			//we still need an object to reference when checking the variant store, even if it's empty.
			VariantStore variantStore = new VariantStore();
			variantStore.setPatientIds(new String[0]);
			return variantStore;
		}
	}

	public Map<String, int[]> countVariants() {
		HashMap<String, Integer> countOffsetMap = new HashMap<String, Integer>();
		TreeMap<String, int[]> counts = new TreeMap<>();
		for (String contig : variantMaskStorage.keySet()) {
			counts.put(contig, new int[5]);
			FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> storage = variantMaskStorage
					.get(contig);
			storage.keys().stream().forEach((Integer key) -> {
				int[] contigCounts = counts.get(contig);
				try {
					Collection<VariantMasks> values = storage.get(key).values();
					contigCounts[0] += values.stream().collect(Collectors.summingInt((VariantMasks masks) -> {
						return masks.heterozygousMask != null ? 1 : 0;
					}));
					contigCounts[1] += values.stream().collect(Collectors.summingInt((VariantMasks masks) -> {
						return masks.homozygousMask != null ? 1 : 0;
					}));
					contigCounts[2] += values.stream().collect(Collectors.summingInt((VariantMasks masks) -> {
						return masks.heterozygousNoCallMask != null ? 1 : 0;
					}));
					contigCounts[3] += values.stream().collect(Collectors.summingInt((VariantMasks masks) -> {
						return masks.homozygousNoCallMask != null ? 1 : 0;
					}));
					contigCounts[4] += values.stream().collect(Collectors.summingInt((VariantMasks masks) -> {
						return masks.heterozygousMask != null || masks.homozygousMask != null
								|| masks.heterozygousNoCallMask != null || masks.homozygousNoCallMask != null ? 1 : 0;
					}));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
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

	public VariantMasks getMasks(String variant, VariantBucketHolder<VariantMasks> bucketCache) throws IOException {
		String[] segments = variant.split(",");
		if (segments.length < 2) {
			log.error("Less than 2 segments found in this variant : " + variant);
		}

		int chrOffset = Integer.parseInt(segments[1]) / BUCKET_SIZE;
		String contig = segments[0];

//		if (Level.DEBUG.equals(log.getEffectiveLevel())) {
//			log.debug("Getting masks for variant " + variant + "  Same bucket test: " + (bucketCache.lastValue != null
//					&& contig.contentEquals(bucketCache.lastContig) && chrOffset == bucketCache.lastChunkOffset));
//		}

		if (bucketCache.lastValue != null && contig.contentEquals(bucketCache.lastContig)
				&& chrOffset == bucketCache.lastChunkOffset) {
			// TODO : This is a temporary efficiency hack, NOT THREADSAFE!!!
		} else {
			bucketCache.lastValue = variantMaskStorage.get(contig).get(chrOffset);
			bucketCache.lastContig = contig;
			bucketCache.lastChunkOffset = chrOffset;
		}
		return bucketCache.lastValue == null ? null : bucketCache.lastValue.get(variant);
	}

	public String[] getHeaders() {
		return vcfHeaders;
	}

	public void open() {
		variantMaskStorage.values().stream().forEach((fbbis -> {
			if (fbbis != null) {
				try {
					fbbis.open();
				} catch (FileNotFoundException e) {
					throw new UncheckedIOException(e);
				}
			}
		}));
	}

	public VariantStore() {

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

	public List<VariantMasks> getMasksForRangesOfChromosome(String contigForGene, List<Integer> offsetsForGene,
			RangeSet<Integer> rangeSetsForGene) throws IOException {
		FileBackedByteIndexedStorage masksForChromosome = variantMaskStorage.get(contigForGene);
		Set<Integer> bucketsForGene = offsetsForGene.stream().map((offset) -> {
			return offset / BUCKET_SIZE;
		}).collect(Collectors.toSet());
		List<VariantMasks> masks = new ArrayList<VariantMasks>();
		for (Integer bucket : bucketsForGene) {
			Map<String, VariantMasks> variantMaskBucket = (Map<String, VariantMasks>) masksForChromosome.get(bucket);
			variantMaskBucket.keySet().stream().filter((String spec) -> {
				int offsetForVariant = Integer.parseInt(spec.split(",")[1]);
				return rangeSetsForGene.contains(offsetForVariant);
			}).forEach((spec) -> {
				System.out.println(spec);
				masks.add(variantMaskBucket.get(spec));
			});
		}
		return masks;
	}

	public BigInteger emptyBitmask() {
		if (emptyBitmask == null || emptyBitmask.testBit(emptyBitmask.bitLength() / 2)) {
			String emptyVariantMask = "";
			for (String patientId : patientIds) {
				emptyVariantMask = emptyVariantMask + "0";
			}
			emptyBitmask = new BigInteger("11" + emptyVariantMask + "11", 2);
		}
		return emptyBitmask;
	}

}
