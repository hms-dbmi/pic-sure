package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.math.BigInteger;

public class VariantMasks implements Serializable {
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

	public VariantMasks(String[] values) {
		char[] homozygousBits = new char[values.length];
		char[] heterozygousBits = new char[values.length];
		char[] homozygousNoCallBits = new char[values.length];
		char[] heterozygousNoCallBits = new char[values.length];
		boolean hasHetero = false;
		boolean hasHomo = false;
		boolean hasHeteroNoCall = false;
		boolean hasHomoNoCall = false;

		for(int x = 0;x<values.length;x++) {
			homozygousBits[x]=zero;
			heterozygousBits[x]=zero;
			homozygousNoCallBits[x]=zero;
			heterozygousNoCallBits[x]=zero;
			if(values[x]!=null) {
				switch(values[x]) {
				case hetero:{
					heterozygousBits[x] = one;
					hasHetero = true;
					break;
				}
				case heteroPhased:{
					heterozygousBits[x] = one;
					hasHetero = true;
					break;
				}
				case heteroPhased2:{
					heterozygousBits[x] = one;
					hasHetero = true;
					break;
				}
				case heteroDel:{
					heterozygousBits[x] = one;
					hasHetero = true;
					break;
				}
				case homo:{
					homozygousBits[x] = one;
					hasHomo = true;
					break;			
				}
				case homoPhased:{
					homozygousBits[x] = one;
					hasHomo = true;
					break;			
				}
				case heteroNoCall:{
					heterozygousNoCallBits[x] = one;
					hasHeteroNoCall = true;
					break;
				}
				case homoNoCall:{
					homozygousNoCallBits[x] = one;
					hasHomoNoCall = true;
					break;
				}
				}				
			}

		}
		if(hasHetero) {
			StringBuilder heteroString = new StringBuilder(values.length + 4);
			heteroString.append(oneone);
			heteroString.append(heterozygousBits);
			heteroString.append(oneone);
			heterozygousMask = new BigInteger(heteroString.toString(), 2);	
		}
		if(hasHomo) {
			StringBuilder homoString = new StringBuilder(values.length + 4);
			homoString.append(oneone);
			homoString.append(homozygousBits);
			homoString.append(oneone);
			homozygousMask = new BigInteger(homoString.toString(), 2);	
		}
		if(hasHomoNoCall) {
			StringBuilder homoNoCallString = new StringBuilder(values.length + 4);
			homoNoCallString.append(oneone);
			homoNoCallString.append(homozygousNoCallBits);
			homoNoCallString.append(oneone);
			homozygousNoCallMask = new BigInteger(homoNoCallString.toString(), 2);	
		}
		if(hasHeteroNoCall) {
			StringBuilder heteroNoCallString = new StringBuilder(values.length + 4);
			heteroNoCallString.append(oneone);
			heteroNoCallString.append(heterozygousNoCallBits);
			heteroNoCallString.append(oneone);
			heterozygousNoCallMask = new BigInteger(heteroNoCallString.toString(), 2);	
		}
	}
	
	public VariantMasks(char[][] maskValues) {
		String heteroMaskStringRaw = new String(maskValues[ZERO_ONE_CHAR_INDEX]);
		String homoMaskStringRaw = new String(maskValues[ONE_ONE_CHAR_INDEX]);
		String heteroNoCallMaskStringRaw = new String(maskValues[HETERO_NOCALL_VARIANT_CHAR_INDEX]);
		String homoNoCallMaskStringRaw = new String(maskValues[HOMO_NOCALL_CHAR_INDEX]);
		if(heteroMaskStringRaw.contains("1")) {
			heterozygousMask = new BigInteger(oneone + heteroMaskStringRaw + oneone, 2);
		}
		if(homoMaskStringRaw.contains("1")) {
			homozygousMask = new BigInteger(oneone + homoMaskStringRaw + oneone, 2);
		}
		if(heteroNoCallMaskStringRaw.contains("1")) {
			heterozygousNoCallMask = new BigInteger(oneone + heteroNoCallMaskStringRaw + oneone, 2);
		}
		if(homoNoCallMaskStringRaw.contains("1")) {
			homozygousNoCallMask = new BigInteger(oneone + homoNoCallMaskStringRaw + oneone, 2);
		}
		
	}

	public VariantMasks() {
	}

	@JsonProperty("ho")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonSerialize(using = ToStringSerializer.class)
	public BigInteger homozygousMask;
	@JsonProperty("he")
	@JsonSerialize(using = ToStringSerializer.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public BigInteger heterozygousMask;
	@JsonProperty("hon")
	@JsonSerialize(using = ToStringSerializer.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public BigInteger homozygousNoCallMask;
	@JsonProperty("hen")
	@JsonSerialize(using = ToStringSerializer.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public BigInteger heterozygousNoCallMask;
}
