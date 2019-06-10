package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.util.TreeMap;

public class VCFMapping {
	String sampleId;
	String patientId;
	TreeMap<Integer, String> chromosomeFiles;
	boolean gzipped;
}
