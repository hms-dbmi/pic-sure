package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;


import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex.VARIANT_METADATA_FILENAME;
import static edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex.VARIANT_METADATA_STORAGE_FILE_PREFIX;

/**
 * 
 * This loader will read in the metadata associated with each variant and build a VariantMetadataIndex that
 * can be used to populate data in the PIC-SURE Varaint Explorer.
 */
public class SplitChromosomeVariantMetadataLoader {

	public static final String DEFAULT_TSV_FILENAME = "vcfIndex.tsv";
	public static final String DEFAULT_HPDS_DIRECTORY = "/opt/local/hpds/";

	public static final String DEFAULT_HPDS_DATA_DIRECTORY = DEFAULT_HPDS_DIRECTORY + "all/";
	private static Logger log = LoggerFactory.getLogger(SplitChromosomeVariantMetadataLoader.class);
	
	//fields to allow tests to override default file location
	private static String hpdsDataPath = null;
	
	private static final int
	ANNOTATED_FLAG_COLUMN = 2,
	GZIP_FLAG_COLUMN=3,
	FILE_COLUMN = 0;	
	
	public static void main(String[] args) throws IOException {
		File vcfIndexFile;
		
		log.info(new File(".").getAbsolutePath());
		if(args.length > 0 && new File(args[0]).exists()) {
			log.info("using path from command line");
			vcfIndexFile = new File(args[0]);
			hpdsDataPath = args[1];
		}else {
			hpdsDataPath = DEFAULT_HPDS_DATA_DIRECTORY;
			vcfIndexFile = new File(DEFAULT_HPDS_DIRECTORY + DEFAULT_TSV_FILENAME);
		}
		
		List<VcfInputFile> vcfIndexFiles = new ArrayList<>();

		try(CSVParser parser = CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true))) { 
			final boolean[] horribleHeaderSkipFlag = {false}; 
			parser.forEach((CSVRecord r)->{
				if(horribleHeaderSkipFlag[0]) {
					File vcfFileLocal = new File(r.get(FILE_COLUMN)); 
					if(Integer.parseInt(r.get(ANNOTATED_FLAG_COLUMN).trim())==1) {
						VcfInputFile vcfInput = new VcfInputFile(vcfFileLocal, (Integer.parseInt(r.get(GZIP_FLAG_COLUMN).trim())==1));
						vcfIndexFiles.add(vcfInput);
					}
				}else {
					horribleHeaderSkipFlag[0] = true;
				}
			});
		}

		vcfIndexFiles.stream().forEach(SplitChromosomeVariantMetadataLoader::createVariantMetadataIndexForContig);


	}

	private static void createVariantMetadataIndexForContig(VcfInputFile vcfInput) {
		try {
			String contig = vcfInput.currentContig;
			VariantMetadataIndex metadataIndex = new VariantMetadataIndex(hpdsDataPath + contig + "/" + VARIANT_METADATA_STORAGE_FILE_PREFIX);

			while (vcfInput.hasNextVariant()) {
				metadataIndex.put(vcfInput.currentVariantSpec, vcfInput.currentMetaData);
				vcfInput.nextVariant();
			}
			metadataIndex.put(vcfInput.currentVariantSpec, vcfInput.currentMetaData);

			metadataIndex.complete();

			//store this in a path per contig (or a preset path
			String binfilePath = hpdsDataPath + contig + "/" + VARIANT_METADATA_FILENAME;

			try(ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(binfilePath))))){
				out.writeObject(metadataIndex);
				out.flush();
			}

			log.info("Finished processing:  "+ vcfInput.fileName);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
