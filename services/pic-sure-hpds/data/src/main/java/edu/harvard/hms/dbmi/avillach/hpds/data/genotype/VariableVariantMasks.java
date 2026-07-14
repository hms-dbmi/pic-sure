package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VariableVariantMasks implements Serializable {

	private static Logger log = LoggerFactory.getLogger(VariableVariantMasks.class);

	private static final long serialVersionUID = 6225420483804601477L;
	private static final String oneone = "11";
	private static final char one = '1';
	private static final char zero = '0';
	private static final String hetero = "0/1";
	private static final String heteroDel = "1/0";
	private static final String heteroPhased = "0|1";
	private static final String heteroPhased2 = "1|0";
	private static final String homo = "1/1";
	private static final String homoPhased = "1|1";
	private static final String homoNoCall = "./.";
	private static final String heteroNoCall = "./1";

	private static Map<Integer, BigInteger> emptyBitmaskMap = new ConcurrentHashMap<>();

	public static int SPARSE_VARIANT_THRESHOLD = 5;

	/*
	 * These indices result from the char values of the 3 characters in a VCF sample
	 * record summed as integers % 7
	 *
	 * This allows us to not actually do string comparisons, instead we add 3 values,
	 * do one modulus operation, then use the result as the index into the result array.
	 */

	// ./0 = (46 + 47 + 48) % 7 = 1
	// 0|. = (48 + 124 + 46) % 7 = 1
	// .|0 = (46 + 124 + 48) % 7 = 1
	public static final int HETERO_NOCALL_REF_CHAR_INDEX = 1;

	// ./1 = (46 + 47 + 49) % 7 = 2
	// 1|. = (49 + 124 + 46) % 7 = 2
	// .|1 = (46 + 124 + 49) % 7 = 2
	// ./1 = (46 + 47 + 49) % 7 = 2
	// 1|. = (49 + 124 + 46) % 7 = 2
	// .|1 = (46 + 124 + 49) % 7 = 2
	public static final int HETERO_NOCALL_VARIANT_CHAR_INDEX = 2;

	// 0/0 = (48 + 47 + 48) % 7 = 3
	// 0|0 = (48 + 124 + 48) % 7 = 3
	public static final int ZERO_ZERO_CHAR_INDEX = 3;

	// 0/1 = (48 + 47 + 49) % 7 = 4
	// 1|0 = (49 + 124 + 48) % 7 = 4
	// 0|1 = (48 + 124 + 49) % 7 = 4
	public static final int ZERO_ONE_CHAR_INDEX = 4;

	// 1/1 = (49 + 47 + 49) % 7 = 5
	// 1|1 = (49 + 124 + 49) % 7 = 5
	public static final int ONE_ONE_CHAR_INDEX = 5;

	// ./. = (46 + 47 + 46) % 7 = 6
	// .|. = (46 + 124 + 46) % 7 = 6
	public static final int HOMO_NOCALL_CHAR_INDEX = 6;

	public VariableVariantMasks(char[][] maskValues) {
		String heteroMaskStringRaw = new String(maskValues[ZERO_ONE_CHAR_INDEX]);
		String homoMaskStringRaw = new String(maskValues[ONE_ONE_CHAR_INDEX]);
		String heteroNoCallMaskStringRaw = new String(maskValues[HETERO_NOCALL_VARIANT_CHAR_INDEX]);
		String homoNoCallMaskStringRaw = new String(maskValues[HOMO_NOCALL_CHAR_INDEX]);

		heterozygousMask = variantMaskFromRawString(heteroMaskStringRaw);
		homozygousMask = variantMaskFromRawString(homoMaskStringRaw);
		heterozygousNoCallMask = variantMaskFromRawString(heteroNoCallMaskStringRaw);
		homozygousNoCallMask = variantMaskFromRawString(homoNoCallMaskStringRaw);
	}

	private VariantMask variantMaskFromRawString(String maskStringRaw) {
		if (!maskStringRaw.contains("1")) {
			return null;
		}

		VariantMask variantMask;
		BigInteger bitmask = new BigInteger(oneone + new StringBuilder(maskStringRaw).reverse() + oneone, 2);
		if (bitmask.bitCount() - 4 > VariableVariantMasks.SPARSE_VARIANT_THRESHOLD) {
			variantMask = new VariantMaskBitmaskImpl(bitmask);
		} else {
			Set<Integer> patientIndexes = new HashSet<>();
			for(int i = 0; i < bitmask.bitLength() - 4; i++) {
				// i + 2 because the mask is padded with 2 bits on each end
				if (bitmask.testBit(i + 2)) {
					patientIndexes.add(i);
				}
			}
			variantMask = new VariantMaskSparseImpl(patientIndexes);
		}
		return variantMask;
	}

	public VariableVariantMasks() {
	}

	@JsonProperty("ho")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public VariantMask homozygousMask;

	@JsonProperty("he")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public VariantMask heterozygousMask;

	@JsonProperty("hon")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public VariantMask homozygousNoCallMask;

	@JsonProperty("hen")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public VariantMask heterozygousNoCallMask;

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		VariableVariantMasks that = (VariableVariantMasks) o;
		return Objects.equals(homozygousMask, that.homozygousMask) && Objects.equals(heterozygousMask, that.heterozygousMask) && Objects.equals(homozygousNoCallMask, that.homozygousNoCallMask) && Objects.equals(heterozygousNoCallMask, that.heterozygousNoCallMask);
	}

	@Override
	public int hashCode() {
		return Objects.hash(homozygousMask, heterozygousMask, homozygousNoCallMask, heterozygousNoCallMask);
	}

	public static BigInteger emptyBitmask(int length) {
		BigInteger emptyBitmask = emptyBitmaskMap.get(length);
		if (emptyBitmask == null) {
			String emptyVariantMask = "";
			for (int i = 0; i < length; i++) {
				emptyVariantMask = emptyVariantMask + "0";
			}
			BigInteger newEmptyBitmask = new BigInteger("11" + emptyVariantMask + "11", 2);
			emptyBitmaskMap.put(length, newEmptyBitmask);
			return newEmptyBitmask;
		}
		return emptyBitmask;
	}

	public static VariableVariantMasks append(VariableVariantMasks masks1, int length1, VariableVariantMasks masks2, int length2) {
		VariableVariantMasks appendedMasks = new VariableVariantMasks();
		appendedMasks.homozygousMask = appendMask(masks1.homozygousMask, masks2.homozygousMask, length1, length2);
		appendedMasks.heterozygousMask = appendMask(masks1.heterozygousMask, masks2.heterozygousMask, length1, length2);
		appendedMasks.homozygousNoCallMask = appendMask(masks1.homozygousNoCallMask, masks2.homozygousNoCallMask, length1, length2);
		appendedMasks.heterozygousNoCallMask = appendMask(masks1.heterozygousNoCallMask, masks2.heterozygousNoCallMask, length1, length2);
		return appendedMasks;
	}

	public static VariantMask appendMask(VariantMask variantMask1, VariantMask variantMask2, int length1, int length2) {
		if (variantMask1 == null) {
			variantMask1 = VariantMask.emptyInstance();
		}
		if (variantMask2 == null) {
			variantMask2 = VariantMask.emptyInstance();
		}
		if (variantMask1.equals(VariantMask.emptyInstance()) && variantMask2.equals(VariantMask.emptyInstance())) {
			return null;
		}

		if (variantMask1 instanceof  VariantMaskSparseImpl) {
			if (variantMask2 instanceof VariantMaskSparseImpl) {
				return append((VariantMaskSparseImpl) variantMask1, (VariantMaskSparseImpl) variantMask2, length1, length2);
			} else if (variantMask2 instanceof  VariantMaskBitmaskImpl) {
				return append((VariantMaskSparseImpl) variantMask1, (VariantMaskBitmaskImpl) variantMask2, length1, length2);
			} else {
				throw new RuntimeException("Unknown VariantMask implementation");
			}
		}
		else if (variantMask1 instanceof  VariantMaskBitmaskImpl) {
			if (variantMask2 instanceof VariantMaskSparseImpl) {
				return append((VariantMaskBitmaskImpl) variantMask1, (VariantMaskSparseImpl) variantMask2, length1, length2);
			} else if (variantMask2 instanceof  VariantMaskBitmaskImpl) {
				return append((VariantMaskBitmaskImpl) variantMask1, (VariantMaskBitmaskImpl) variantMask2, length1, length2);
			} else {
				throw new RuntimeException("Unknown VariantMask implementation");
			}
		}
		else {
			throw new RuntimeException("Unknown VariantMask implementation");
		}
	}

	private static VariantMask append(VariantMaskSparseImpl variantMask1, VariantMaskBitmaskImpl variantMask2, int length1, int length2) {
		BigInteger mask1 = emptyBitmask(length1);
		for (Integer patientIndex : variantMask1.patientIndexes) {
			mask1 = mask1.setBit(patientIndex + 2);
		}
		String binaryMask1 = mask1.toString(2);

		String binaryMask2 = variantMask2.bitmask.toString(2);
		if (binaryMask2.length() - 4 != length2) {
			throw new IllegalArgumentException("Bitmask does not match length (" + length2 + "): " + variantMask2.bitmask);
		}

		String appendedString = binaryMask2.substring(0, binaryMask2.length() - 2) +
				binaryMask1.substring(2);
		return new VariantMaskBitmaskImpl(new BigInteger(appendedString, 2));
	}

	private static VariantMask append(VariantMaskBitmaskImpl variantMask1, VariantMaskSparseImpl variantMask2, int length1, int length2) {
		String binaryMask1 = variantMask1.bitmask.toString(2);
		if (binaryMask1.length() - 4 != length1) {
			throw new IllegalArgumentException("Bitmask does not match length (" + length1 + "): " + variantMask1.bitmask);
		}

		BigInteger mask2 = emptyBitmask(length2);
		for (Integer patientId : variantMask2.patientIndexes) {
			mask2 = mask2.setBit(patientId + 2);
		}
		String binaryMask2 = mask2.toString(2);

		String appendedString = binaryMask2.substring(0, binaryMask2.length() - 2) +
				binaryMask1.substring(2);
		return new VariantMaskBitmaskImpl(new BigInteger(appendedString, 2));
	}

	private static VariantMask append(VariantMaskBitmaskImpl variantMask1, VariantMaskBitmaskImpl variantMask2, int length1, int length2) {
		String binaryMask1 = variantMask1.bitmask.toString(2);
		String binaryMask2 = variantMask2.bitmask.toString(2);

		if (binaryMask1.length() - 4 != length1) {
			throw new IllegalArgumentException("Bitmask does not match length (" + length1 + "): " + variantMask1.bitmask);
		}
		if (binaryMask2.length() - 4 != length2) {
			throw new IllegalArgumentException("Bitmask does not match length (" + length2 + "): " + variantMask2.bitmask);
		}

		String appendedString = binaryMask2.substring(0, binaryMask2.length() - 2) +
				binaryMask1.substring(2);
		BigInteger bitmask = new BigInteger(appendedString, 2);
		return new VariantMaskBitmaskImpl(bitmask);
	}

	private static VariantMask append(VariantMaskSparseImpl variantMask1, VariantMaskSparseImpl variantMask2, int length1, int length2) {
		if (variantMask1.patientIndexes.size() + variantMask2.patientIndexes.size() > SPARSE_VARIANT_THRESHOLD) {
			BigInteger mask = emptyBitmask(length1 + length2);
			for (Integer patientId : variantMask1.patientIndexes) {
				mask = mask.setBit(patientId + 2);
			}
			// We start writing mask 2 where mask 1 ends. So the 0th index of mask 2 is now following the last bit of mask 1
			for (Integer patientId : variantMask2.patientIndexes) {
				mask = mask.setBit(patientId + length1 + 2);
			}
			return new VariantMaskBitmaskImpl(mask);
		}
		else {
			Set<Integer> patientIndexSet = new HashSet<>();
			patientIndexSet.addAll(variantMask1.patientIndexes);
			// The indexes for mask 2 are shifted by the length of mask 1, corresponding to the corresponding patient id array
			// for mask 2 being appended to those of mask 1
			patientIndexSet.addAll(variantMask2.patientIndexes.stream().map(i -> i + length1).collect(Collectors.toSet()));
			return new VariantMaskSparseImpl(patientIndexSet);
		}
	}

}
