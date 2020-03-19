package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.log4j.Logger;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import htsjdk.samtools.util.BlockCompressedInputStream;

/**
 * 
 * To be implemented as part of ALS-112
 * 
 */
public class VariantMetadataLoader {
	
	private static Logger log = Logger.getLogger(VariantMetadataLoader.class);
	public static String binFile = "/opt/local/hpds/all/VariantMetadata.javabin";
    
	private static final int 
	INFO_COLUMN = 7,
	ANNOTATED_FLAG_COLUMN = 2,
	GZIP_FLAG_COLUMN=3,
	FILE_COLUMN = 0;	
	
	public static void main(String[] args) throws Exception{  
		File vcfIndexFile;
		String storagePathForTests = null;
		log.info(new File(".").getAbsolutePath());
		if(args.length > 0 && new File(args[0]).exists()) {
			log.info("using path from command line, is this a test");
			vcfIndexFile = new File(args[0]);
			binFile = args[1];
			storagePathForTests = args[2];
		}else {
			vcfIndexFile = new File("/opt/local/hpds/vcfIndex.tsv");
		}
		List<File> vcfFiles = new ArrayList<>();
		List<Boolean> gzipFlags = new ArrayList<>();

		try(CSVParser parser = CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true))) { 
			final boolean[] horribleHeaderSkipFlag = {false}; 
			parser.forEach((CSVRecord r)->{
				if(horribleHeaderSkipFlag[0]) {
					File vcfFileLocal = new File(r.get(FILE_COLUMN)); 
					if(Integer.parseInt(r.get(ANNOTATED_FLAG_COLUMN).trim())==1) {
						vcfFiles.add(vcfFileLocal);
						gzipFlags.add(Integer.parseInt(r.get(GZIP_FLAG_COLUMN).trim())==1);
					}
				}else {
					horribleHeaderSkipFlag[0] = true;
				}
			});
		}
		
		VariantMetadataIndex vmi = storagePathForTests == null ? 
				new VariantMetadataIndex() : new VariantMetadataIndex(storagePathForTests); 
				
		for(int x = 0;x<vcfFiles.size();x++){
			processVCFFile(vmi, vcfFiles.get(x), gzipFlags.get(x)); 
			
		}

		vmi.complete(); 
		
		try(ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(binFile))))){
			out.writeObject(vmi);
			out.flush();
		}
		
	}
	 
	public static void processVCFFile(VariantMetadataIndex vmi, File vcfFile, boolean gzipFlag) {  
		log.info("Processing VCF file:  "+vcfFile.getName());   
		try(Reader reader = new InputStreamReader( 
				gzipFlag ? new GZIPInputStream(new FileInputStream(vcfFile)) : new FileInputStream(vcfFile));
				CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(false))){
			Iterator<CSVRecord> iterator = parser.iterator();   
			boolean isRowData = false;  
			while(iterator.hasNext()) { 
			    CSVRecord csvRecord = iterator.next(); 
			    if(!isRowData) {
			    	if(csvRecord.get(0).startsWith("#CHROM")) {  
				    	csvRecord = iterator.next();
				    	isRowData = true;
				    }
			    }
			      
			    if(isRowData) { 
			    	VariantSpec variantSpec = new VariantSpec(csvRecord); 
			    	vmi.put(variantSpec.specNotation(), List.of(csvRecord.get(INFO_COLUMN).trim()).stream().toArray(size -> new String[size]));
			    } 
			} 			
		}catch(IOException e) {
			log.error("Error processing VCF file: "+vcfFile.getName(), e);
		}
	}   
}
