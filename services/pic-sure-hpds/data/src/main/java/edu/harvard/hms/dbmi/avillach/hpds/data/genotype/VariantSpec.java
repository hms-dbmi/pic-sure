package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.Serializable;

import org.apache.commons.csv.CSVRecord;

public class VariantSpec implements Serializable {

	private static final long serialVersionUID = 1289657089351754207L;

	public class VariantCoords implements Serializable {
		private static final long serialVersionUID = -9105056750599920709L;
		public Integer chromosome;
		public Integer offset;
		public String name;
		public String ref;
		public String alt;
		public int qual;
		public String format;		
	}

	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7;
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
		this.metadata.qual = Integer.parseInt(r.get(QUAL));
	}

	public String specNotation() {
		return this.metadata.chromosome + "," 
				+ this.metadata.offset + "," + 
				this.metadata.ref + "," + this.metadata.alt;
	}

}

