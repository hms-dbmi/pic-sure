package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching;

import java.util.concurrent.ConcurrentHashMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;

public class VariantMaskBucketHolder {
	public ConcurrentHashMap<String, VariantMasks> lastSetOfVariants;
	public String lastContig;
	public int lastChunkOffset;	
}
