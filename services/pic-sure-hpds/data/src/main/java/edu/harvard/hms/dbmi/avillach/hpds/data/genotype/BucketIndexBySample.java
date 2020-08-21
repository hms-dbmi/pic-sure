package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class BucketIndexBySample implements Serializable {
	private static final long serialVersionUID = -1230735595028630687L;
	public static final String INDEX_FILE = "/opt/local/hpds/all/BucketIndexBySample.javabin";
	private static final String STORAGE_FILE = "/opt/local/hpds/all/BucketIndexBySampleStorage.javabin";
	private static final int CONTIG_SCALE = 1000000;
	private FileBackedByteIndexedStorage<Integer, HashSet<Integer>> fbbis;  
	// 2147483647
	// 2999333333

	List<Integer> patientIds;
	ArrayList<String> contigSet;

	transient Logger log = Logger.getLogger(BucketIndexBySample.class);

	public BucketIndexBySample(VariantStore variantStore) throws FileNotFoundException {
		fbbis = new FileBackedByteIndexedStorage(Integer.class, HashSet.class, new File(STORAGE_FILE));
		HashMap<Integer,HashSet<Integer>> index = new HashMap<Integer, HashSet<Integer>>();
		contigSet = new ArrayList<String>(variantStore.variantMaskStorage.keySet());
		if(contigSet.size() > 2000) {
			throw new RuntimeException("Too many contigs");
		}
		patientIds = Arrays.stream(variantStore.getPatientIds()).map(id->{return Integer.parseInt(id);}).collect(Collectors.toList());
		for(int patientId : patientIds) {
			index.put(patientId, new HashSet<Integer>());
		}
		BigInteger emptyBitmask = variantStore.emptyBitmask();
		for(String contig : contigSet) {
			int contigInteger = contigSet.indexOf(contig) * CONTIG_SCALE;
			variantStore.variantMaskStorage.get(contig).keys().stream().forEach((bucket)->{
				try {
					variantStore.variantMaskStorage.get(contig).get(bucket).forEach((variantSpec, masks)->{
						BigInteger mask =
								(masks.homozygousMask == null ? emptyBitmask : masks.homozygousMask).or(
										(masks.heterozygousMask == null ? emptyBitmask : masks.heterozygousMask));
						patientIds.parallelStream().forEach((id)->{
							if(mask.testBit(patientIds.indexOf(id)+2)) {
								index.get(id).add(contigInteger + bucket);
							}
						});
					});
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		fbbis.open();
		index.forEach((patientId, bucketSet)->{
			try {
				fbbis.put(patientId, bucketSet);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		fbbis.complete();
	}

	public TreeSet<String> filterVariantSetForPatientSet(Set<String> variantSet, List<Integer> patientSet){
		Integer firstPatientId = patientSet.iterator().next();
		patientSet.remove(firstPatientId);
		Set<Integer>[] bucketSet = new Set[] {getBucketSetForPatientId(firstPatientId)};
		patientSet.stream().forEach((id)->{
			bucketSet[0] = Sets.intersection(bucketSet[0], getBucketSetForPatientId(id));
		});
		ConcurrentMap<Integer, List<String>> bucketMap = variantSet.parallelStream().
				collect(Collectors.groupingByConcurrent((String variantSpec)->{
					String[] spec = variantSpec.split(",");
					return (contigSet.indexOf(spec[0]) * CONTIG_SCALE) + (Integer.parseInt(spec[1])/1000);
				}));
		ConcurrentSkipListSet<String> filteredVariantSet = new ConcurrentSkipListSet<>();
		bucketSet[0].parallelStream().forEach((bucket)->{
			List<String> variantBucket = bucketMap.get(bucket);
			if(variantBucket!=null) {
				filteredVariantSet.addAll(variantBucket);
			}
		});
		return new TreeSet<>(filteredVariantSet);
	}

	private HashSet<Integer> getBucketSetForPatientId(Integer patientId) {
		try {
			return  fbbis.get(patientId);
		} catch (IOException e) {
			log.error(e);
		}
		return new HashSet<Integer>();
	}

}