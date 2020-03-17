package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;

/**
 * 
 * To be implemented as part of ALS-112
 * 
 */
public class VariantMetadataLoader {

	private static final int 
	INFO_COLUMN = 7,  
	FILE_COLUMN = 0,
	CHROMOSOME_COLUMN = 1,
	ANNOTATED_FLAG_COLUMN = 2,
	GZIP_FLAG_COLUMN=3,
	SAMPLE_IDS_COLUMN=4,
	PATIENT_IDS_COLUMN=5,
	//These columns are to support a future feature, ignore them for now.
	SAMPLE_RELATIONSHIPS_COLUMN=6,
	RELATED_SAMPLE_IDS_COLUMN=7;	
	
	public static void main(String[] args) throws Exception{ 
		/*
		File vcfIndexFile = new File("/opt/local/hpds/vcfIndex.tsv");
		List<File> vcfFiles = new ArrayList<>();
 		CSVParser parser;
		try {
			parser = CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true)); 
			final boolean[] horribleHeaderSkipFlag = {false}; 
			parser.forEach((CSVRecord r)->{
				if(horribleHeaderSkipFlag[0]) {
					File vcfFileLocal = new File(r.get(FILE_COLUMN)); 
					vcfFiles.add(vcfFileLocal);
				}else {
					horribleHeaderSkipFlag[0] = true;
				}
			});
		}catch(Exception e) {
			e.printStackTrace();
		}
		
		vcfFiles.stream().forEach(System.out::println);
		
		*/
		
		String vcfFile = "/opt/local/hpds/vcfInput/chr14.1kg.phase3_small.20130502.vcf"; 
		processFile(vcfFile);
	}
	 
	public static void processFile(String vcfFile) throws Exception {  
		VariantMetadataIndex vmi = new VariantMetadataIndex(); 
		
		CSVParser parser = null;
		Reader reader = null;
		try {
			reader = Files.newBufferedReader(Paths.get(vcfFile));
			parser = new CSVParser(reader, CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(false)); 
			Iterator<CSVRecord> iterator = parser.iterator();   
			boolean isRowData = false;  
			while(iterator.hasNext()) { 
			    CSVRecord csvRecord = iterator.next(); 
			    if(csvRecord.get(0).startsWith("#CHROM")) {  
			    	csvRecord = iterator.next();
			    	isRowData = true;
			    } 
			    
			    if(isRowData) { 
			    	VariantSpec variantSpec = new VariantSpec(csvRecord); 
			    	vmi.put(variantSpec.specNotation(), List.of(csvRecord.get(INFO_COLUMN).trim()).stream().toArray(size -> new String[size])); 
			    } 
			} 
			vmi.complete(); 
			
			ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(VariantMetadataIndex.binFile))));
			out.writeObject(vmi);
			out.close();
			
			
		} catch (Exception e) {
			parser.close();
			e.printStackTrace();
		} 
	}   
}
