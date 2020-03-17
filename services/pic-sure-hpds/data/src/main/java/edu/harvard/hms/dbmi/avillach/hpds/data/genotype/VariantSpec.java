package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.Serializable;

import org.apache.commons.csv.CSVRecord;

public class VariantSpec implements Serializable, Comparable<VariantSpec> {

	public class VariantCoords implements Serializable {
		public Integer chromosome;
		public Integer offset;
		public String name;
		public String ref;
		public String alt;
		public int qual;
		public String format;		
	}
	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7, FORMAT = 8, DATA = 9;
	public long heteroOffset;
	public long homoOffset;
	public VariantCoords metadata;

	public VariantSpec(CSVRecord r) {
		this.metadata = new VariantCoords();
		this.metadata.chromosome = Integer.parseInt(r.get(CHR));
		this.metadata.offset = Integer.parseInt(r.get(OFF));
		this.metadata.name = r.get(NAME);
		this.metadata.ref = r.get(REF);
		this.metadata.alt = r.get(ALT);
		try {
			this.metadata.qual = Integer.parseInt(r.get(QUAL));
		}catch(NumberFormatException e) { 
			this.metadata.qual  = -1;
		}
	}

	public VariantSpec(String variant) {
		this.metadata = new VariantCoords();
		String[] segments = variant.split(",");
		this.metadata.chromosome = Integer.parseInt(segments[0]);
		this.metadata.offset = Integer.parseInt(segments[1]);
		this.metadata.name = null;
		this.metadata.ref = segments[2];
		this.metadata.alt = segments[3];
		this.metadata.qual = -1;
	}

	public String specNotation() {
		return this.metadata.chromosome + "," 
				+ this.metadata.offset + "," + 
				this.metadata.ref + "," + this.metadata.alt;
	}

	@Override
	public int compareTo(VariantSpec o) {
		int ret = 0;
		ret = this.metadata.chromosome.compareTo(o.metadata.chromosome);
		if(ret == 0) {
			ret = this.metadata.offset.compareTo(o.metadata.offset);
		}
		if(ret == 0) {
			ret = this.metadata.alt.compareTo(o.metadata.alt);
		}
		return ret;
	}

}