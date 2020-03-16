package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
	CHROMOSOME_COLUMN = 0,
	POS_COLUMN = 1,
	REF_ALLELES_COLUMN = 3,
	ALT_ALLELES_COLUMN = 4,
	INFO_COLUMN = 7;
	
	 
	public static void main(String[] args) throws FileNotFoundException, IOException, ClassNotFoundException { 
		String vcfFile = "/opt/local/hpds/vcfInput/chr14.1kg.phase3_small.20130502.vcf"; 
		VariantMetadataIndex vmi = new VariantMetadataIndex();  
		vmi.initializeWrites();
		
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
			    	String key = csvRecord.get(CHROMOSOME_COLUMN).trim() +  "," + csvRecord.get(POS_COLUMN).trim() +  "," + csvRecord.get(REF_ALLELES_COLUMN).trim() +  "," + csvRecord.get(ALT_ALLELES_COLUMN).trim();
			    	String value  = csvRecord.get(INFO_COLUMN).trim(); 
			    	VariantSpec variantSpec = new VariantSpec(key); 
			    	
			    	//TODO:: Handle multiple info values 
			    	List<String> valueList = new ArrayList<String>();
			    	valueList.add(value); 
			    	vmi.put(variantSpec.specNotation(), valueList.stream().toArray(size -> new String[size])); 
			    } 
			} 
			vmi.complete(); 
			vmi.writeObject(); 
		} catch (Exception e) {
			parser.close();
			e.printStackTrace();
		}

	}   
}
