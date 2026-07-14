package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.Serializable;
import java.util.Objects;

import org.apache.commons.csv.CSVRecord;

public class VariantSpec implements Serializable, Comparable<VariantSpec> {

	public class VariantCoords implements Serializable {
		public String chromosome;
		public Integer offset;
		public String name;
		public String ref;
		public String alt;
		public int qual;
		public String format;
		public String gene;
		public String consequence;

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			VariantCoords that = (VariantCoords) o;
			return qual == that.qual && Objects.equals(chromosome, that.chromosome) && Objects.equals(offset, that.offset) && Objects.equals(name, that.name) && Objects.equals(ref, that.ref) && Objects.equals(alt, that.alt) && Objects.equals(format, that.format) && Objects.equals(gene, that.gene) && Objects.equals(consequence, that.consequence);
		}

		@Override
		public int hashCode() {
			return Objects.hash(chromosome, offset, name, ref, alt, qual, format, gene, consequence);
		}
	}
	public static int CHR = 0, OFF = 1, NAME = 2, REF = 3, ALT = 4, QUAL = 5, FILTER = 6, INFO = 7, FORMAT = 8, DATA = 9;
	public long heteroOffset;
	public long homoOffset;
	public VariantCoords metadata;

	public VariantSpec(CSVRecord r) {
		this.metadata = new VariantCoords();
		this.metadata.chromosome = r.get(CHR);
		this.metadata.offset = Integer.parseInt(r.get(OFF));
		this.metadata.name = r.get(NAME);
		this.metadata.ref = r.get(REF);
		this.metadata.alt = r.get(ALT);
		try {
			this.metadata.qual = Integer.parseInt(r.get(QUAL));
		}catch(NumberFormatException e) { 
			this.metadata.qual  = -1;
		}

		String[] variantInfo = r.get(INFO).split("[=;]");
		String gene = "NULL";
		String consequence = "NULL";
		for (int i = 0; i < variantInfo.length; i = i + 2) {
			if ("Gene_with_variant".equals(variantInfo[i])) {
				gene = variantInfo[i + 1];
			}
			if ("Variant_consequence_calculated".equals(variantInfo[i])) {
				consequence = variantInfo[i + 1];
			}
		}
		this.metadata.gene = gene;
		this.metadata.consequence = consequence;
	}

	public VariantSpec(String variant) {
		this.metadata = new VariantCoords();
		String[] segments = variant.split(",");
		this.metadata.chromosome = segments[0];
		this.metadata.offset = Integer.parseInt(segments[1]);
		this.metadata.name = null;
		this.metadata.ref = segments[2];
		this.metadata.alt = segments[3];
		this.metadata.qual = -1;
		this.metadata.gene = segments[4];
		this.metadata.consequence = segments[5];
	}

	public String specNotation() {
		return this.metadata.chromosome + "," 
				+ this.metadata.offset + "," + 
				this.metadata.ref + "," + this.metadata.alt + "," + this.metadata.gene + "," + this.metadata.consequence;
	}

	public int compareTo(VariantSpec o) {
		int ret = 0;
		ret = this.metadata.chromosome.compareTo(o.metadata.chromosome);
		if (ret == 0) {
			ret = this.metadata.offset.compareTo(o.metadata.offset);
		}
		if (ret == 0) {
			ret = this.metadata.ref.compareTo(o.metadata.ref);
		}
		if (ret == 0) {
			ret = this.metadata.alt.compareTo(o.metadata.alt);
		}
		if (ret == 0) {
			ret = this.metadata.gene.compareTo(o.metadata.gene);
		}
		if (ret == 0) {
			ret = this.metadata.consequence.compareTo(o.metadata.consequence);
		}

		return ret;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		VariantSpec that = (VariantSpec) o;
		return heteroOffset == that.heteroOffset && homoOffset == that.homoOffset && Objects.equals(metadata, that.metadata);
	}

	@Override
	public int hashCode() {
		return Objects.hash(heteroOffset, homoOffset, metadata);
	}
}