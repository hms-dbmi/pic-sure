package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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

	public Collection<String> filterVariantSetForPatientSet(Set<String> variantSet, List<Integer> patientSet){
		
		// Build a list of buckets represented in the variantSet
		ConcurrentHashMap<Integer,ConcurrentSkipListSet<String>> bucketsFromVariants = new ConcurrentHashMap<Integer, ConcurrentSkipListSet<String>>();
		variantSet.parallelStream().forEach((String variantSpec)->{
			int bucket = bucketFromVariantSpec(variantSpec);
			ConcurrentSkipListSet<String> variantsInBucket = bucketsFromVariants.get(bucket);
			if(variantsInBucket == null) {
				variantsInBucket = new ConcurrentSkipListSet<String>();
				bucketsFromVariants.put(bucket, variantsInBucket);
			}
			variantsInBucket.add(variantSpec);
		});
		Set<Integer> variantBucketSet = bucketsFromVariants.keySet();
		Set<Integer>[]  bucketSetsInScope = new Set[] {variantBucketSet};
		
		patientSet.parallelStream().map((patientId)->{return getBucketSetForPatientId(patientId);}).forEach((bucketSet)->{
			Set patientBucketsInVariantBuckets = Sets.intersection(variantBucketSet, bucketSet);
			synchronized (bucketSetsInScope) {
				bucketSetsInScope[0] = Sets.union(bucketSetsInScope[0], patientBucketsInVariantBuckets);
			}
		});;
		
		return bucketSetsInScope[0].parallelStream().map((bucketSet)->{
			return bucketsFromVariants.get(bucketSet);
		}).flatMap((bucketSet)->{return bucketSet.stream();}).collect(Collectors.toList());
	}

	private Integer bucketFromVariantSpec(String variantSpec) {
		String[] contig_and_offset = new String[2];
		int start = 0;
		int current = 0;
		for(int x = 0;contig_and_offset[1]==null;x++) {
			if(variantSpec.charAt(x)==',') {
				contig_and_offset[current++] = variantSpec.substring(start,x-1);
				start = x+1;
			}
		}
		return (contigSet.indexOf(contig_and_offset[0]) * CONTIG_SCALE) + (Integer.parseInt(contig_and_offset[1])/1000);
	}


	transient LoadingCache<Integer, HashSet<Integer>> bucketSetCache = CacheBuilder.newBuilder()
			.maximumSize(1000).build(new CacheLoader<Integer, HashSet<Integer>>() {
				@Override
				public HashSet<Integer> load(Integer patientId) throws Exception {
					return  fbbis.get(patientId);
				}
			});

	private HashSet<Integer> getBucketSetForPatientId(Integer patientId) {
		//  THIS IS TEMPORARY
		if(bucketSetCache==null) {
			bucketSetCache = CacheBuilder.newBuilder()
					.maximumSize(1000).build(new CacheLoader<Integer, HashSet<Integer>>() {
						@Override
						public HashSet<Integer> load(Integer patientId) throws Exception {
							return  fbbis.get(patientId);
						}
					});
		}
		try {
			return  bucketSetCache.get(patientId);
		} catch (ExecutionException e) {
			log.error(e);
		}
		return new HashSet<Integer>();
	}

}