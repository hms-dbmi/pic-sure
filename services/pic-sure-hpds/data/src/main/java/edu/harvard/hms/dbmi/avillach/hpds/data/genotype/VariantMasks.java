package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

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

	public BigInteger homozygousMask;
	public BigInteger heterozygousMask;
	public BigInteger homozygousNoCallMask;
	public BigInteger heterozygousNoCallMask;
}
