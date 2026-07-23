package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;

public class VcfInputFile implements Comparable<VcfInputFile> {

    private static final int INFO_COLUMN = 7;

    private static Logger log = LoggerFactory.getLogger(VcfInputFile.class);

    Iterator<CSVRecord> iterator;
    CSVParser parser;

    String fileName;
    String currentContig;
    String currentVariantSpec;
    String currentMetaData;

    /**
     * read in an vcfFile, skip the header rows, and queue up the first variant (with metadata)
     * @param vcfFile
     * @param gzipped
     */
    public VcfInputFile(File vcfFile, boolean gzipped) {
        fileName = vcfFile.getName();
        log.info("Processing VCF file:  " + fileName);
        try {
            Reader reader =
                new InputStreamReader(gzipped ? new GZIPInputStream(new FileInputStream(vcfFile)) : new FileInputStream(vcfFile));
            parser = new CSVParser(reader, CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(false));

            iterator = parser.iterator();
            while (iterator.hasNext()) {
                CSVRecord csvRecord = iterator.next();
                // skip all header rows
                if (csvRecord.get(0).startsWith("#")) {
                    continue;
                }

                VariantSpec variantSpec = new VariantSpec(csvRecord);
                currentContig = variantSpec.metadata.chromosome;
                currentVariantSpec = variantSpec.specNotation();
                currentMetaData = csvRecord.get(INFO_COLUMN).trim();
                break;
            }

        } catch (IOException e) {
            log.error("Error processing VCF file: " + vcfFile.getName(), e);
        }

    }

    boolean hasNextVariant() {
        return iterator.hasNext();
    }

    void nextVariant() {
        CSVRecord csvRecord = iterator.next();
        // skip all header rows
        if (csvRecord.get(0).startsWith("#")) {
            return;
        }

        VariantSpec variantSpec = new VariantSpec(csvRecord);
        currentContig = variantSpec.metadata.chromosome;
        currentVariantSpec = variantSpec.specNotation();
        currentMetaData = csvRecord.get(INFO_COLUMN).trim();
    }

    /**
     * These files will be sorted by the current variant spec. We need to make sure they are never actually equal values (since the TreeSet
     * used to keep them sorted enforces uniqueness)
     */
    @Override
    public int compareTo(VcfInputFile arg0) {
        return (currentVariantSpec + iterator.toString()).compareTo(arg0.currentVariantSpec + arg0.iterator.toString());
    }


}
