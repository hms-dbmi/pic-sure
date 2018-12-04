package edu.harvard.hms.dbmi.avillach.geno.data;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;

import org.apache.commons.csv.CSVRecord;

public class VariantMasks implements Serializable {
	private static final String oneone = "11";
	/**
	 * 
	 */
	private static final long serialVersionUID = -844629094106284781L;

	public VariantMasks() {
		
	}
	
	public VariantMasks(CSVRecord record) throws IOException {
		parseVariantData(record);
	}
	
	private static final char one = '1';
	private static final char zero = '0';
	private static final String hetero = "0/1";
	private static final String homo = "1/1";

	// WARNING : This makes parseVariantData NOT threadsafe
	private static transient char[] homozygousBits = new char[1886];
	private static transient char[] heterozygousBits = new char[1886];
	private static transient boolean hasHetero = false;
	private static transient boolean hasHomo = false;
	
	/**
	 * WARNING : Not threadsafe at all
	 * 
	 * @param record
	 * @throws IOException
	 */
	private void parseVariantData(CSVRecord record) throws IOException {
		boolean hasHetero = false;
		boolean hasHomo = false;
		for(int x = 9;x<record.size();x++) {
			String trim = record.get(x);
			if(trim.equalsIgnoreCase(hetero)) {
				heterozygousBits[x-9] = one;
				hasHetero = true;
			}else {
				heterozygousBits[x-9] = zero;
			}
			if(trim.equalsIgnoreCase(homo)) {
				homozygousBits[x-9] = one;
				hasHomo = true;
			}else {
				homozygousBits[x-9] = zero;
			}
		}
		if(hasHetero) {
			StringBuilder heteroString = new StringBuilder(1890);
			heteroString.append(oneone);
			heteroString.append(heterozygousBits);
			heteroString.append(oneone);
			heterozygousMask = new BigInteger(heteroString.toString(), 2);	
		}
		if(hasHomo) {
			StringBuilder homoString = new StringBuilder(1890);
			homoString.append(oneone);
			homoString.append(homozygousBits);
			homoString.append(oneone);
			homozygousMask = new BigInteger(homoString.toString(), 2);	
		}
	}
	
	public BigInteger homozygousMask;
	public BigInteger heterozygousMask;
}
