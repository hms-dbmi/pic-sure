package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import org.apache.commons.csv.CSVRecord;

public class VCFLine implements Comparable<VCFLine> {
	public String patientId;
	public Integer chromosome;
	public Integer offset;
	public String name;
	public String ref;
	public String info;
	public String data;
	public String alt;
	public Double qual;
	public String filter;	
	public String format;		
	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7, FORMAT = 8, DATA = 9;
	
	public VCFLine(CSVRecord r) {
		this.chromosome = Integer.parseInt(r.get(CHR));
		this.offset = Integer.parseInt(r.get(OFF));
		this.name = r.get(NAME);
		this.ref = r.get(REF);
		this.alt = r.get(ALT);
		this.qual = Double.parseDouble(r.get(QUAL));
		this.filter = r.get(FILTER);
		this.format = r.get(FORMAT);
		this.info = r.get(INFO);
		this.data = r.get(DATA);
	}
	
	/** 
	 * Constructor for a VCFLine to parse a specific sample offset from. The sample offset is 0 indexed
	 * starting from the first data column(10th column) of the VCF.
	 * 
	 * @param r a CSV Record
	 * @param sampleIndex The 0-based offset of the sample column to read
	 */
	public VCFLine(CSVRecord r, int sampleIndex, boolean processAnnotations) {
		this.chromosome = Integer.parseInt(r.get(CHR));
		this.offset = Integer.parseInt(r.get(OFF));
		this.name = r.get(NAME);
		this.ref = r.get(REF);
		this.alt = r.get(ALT);
		this.qual = Double.parseDouble(r.get(QUAL));
		this.filter = r.get(FILTER);
		this.format = r.get(FORMAT);
		this.info = processAnnotations ? r.get(INFO) : "";
		this.data = r.get(DATA + sampleIndex);
	}
	
	public VCFLine(String[] line, int sampleIndex, boolean processAnnotations) {
		this.chromosome = Integer.parseInt(line[CHR]);
		this.offset = Integer.parseInt(line[OFF]);
		this.name = line[NAME];
		this.ref = line[REF];
		this.alt = line[ALT];
		this.qual = Double.parseDouble(line[QUAL]);
		this.filter = line[FILTER];
		this.format = line[FORMAT];
		this.info = processAnnotations ? line[INFO] : "";
		this.data = line[DATA + sampleIndex];
	}

	public VCFLine(VCFLine vcfLine) {
		this.chromosome = vcfLine.chromosome;
		this.offset = vcfLine.offset;
		this.name = vcfLine.name;
		this.ref = vcfLine.ref;
		this.alt = vcfLine.alt;
		this.qual = vcfLine.qual;
		this.filter = vcfLine.filter;
		this.format = vcfLine.filter;
		this.info = vcfLine.info;
		this.data = vcfLine.data;
		this.patientId = vcfLine.patientId;
	}

	public VCFLine clone() {
		return new VCFLine(this);
	}
	
	public String specNotation() {
		return this.chromosome + "," 
				+ this.offset + "," + 
				this.ref + "," + this.alt;
	}

	@Override
	public int compareTo(VCFLine o) {
		if(this.chromosome.compareTo(o.chromosome)!=0) {
			return this.chromosome.compareTo(o.chromosome);
		}else {
			if(this.offset.compareTo(o.offset)!=0) {
				return this.offset.compareTo(o.offset);
			}else {
				if(this.alt.compareTo(o.alt)!=0) {
					return this.alt.compareTo(o.alt);
				}else {
					return 0;
				}
			}
		}
	}

}
