package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import org.apache.commons.csv.CSVRecord;

public class VCFLine {
	public Integer chromosome;
	public Integer offset;
	public String name;
	public String ref;
	public String info;
	public String data;
	public String alt;
	public Float qual;
	public String filter;	
	public String format;		
	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7, FORMAT = 8, DATA = 9;
	
	public VCFLine(CSVRecord r) {
		this.chromosome = Integer.parseInt(r.get(CHR));
		this.offset = Integer.parseInt(r.get(OFF));
		this.name = r.get(NAME);
		this.ref = r.get(REF);
		this.alt = r.get(ALT);
		this.qual = Float.parseFloat(r.get(QUAL));
		this.filter = r.get(FILTER);
		this.format = r.get(FORMAT);
		this.info = r.get(INFO);
		this.data = r.get(DATA);
	}
	
	public String specNotation() {
		return this.chromosome + "," 
				+ this.offset + "," + 
				this.ref + "," + this.alt;
	}

}
