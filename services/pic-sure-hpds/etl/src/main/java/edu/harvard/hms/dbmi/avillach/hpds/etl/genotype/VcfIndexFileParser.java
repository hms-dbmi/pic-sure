package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

public class VcfIndexFileParser {


    private static final Logger log = LoggerFactory.getLogger(VcfIndexFileParser.class);

    private static final int FILE_COLUMN = 0;
    private static final int CHROMOSOME_COLUMN = 1;
    private static final int ANNOTATED_FLAG_COLUMN = 2;
    private static final int GZIP_FLAG_COLUMN = 3;
    private static final int SAMPLE_IDS_COLUMN = 4;
    private static final int PATIENT_IDS_COLUMN = 5;



    public List<VCFIndexLine> parse(File vcfIndexFile) {
        TreeSet<VCFIndexLine> vcfSet = new TreeSet<>();
        CSVParser parser;

        int lineNumber = 0;
        try {
            parser =
                CSVParser.parse(vcfIndexFile, Charset.forName("UTF-8"), CSVFormat.DEFAULT.withDelimiter('\t').withSkipHeaderRecord(true));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (CSVRecord r : parser) {
            // Because the CSV parser does not correctly skip the header
            if (lineNumber == 0) {
                lineNumber++;
                continue;
            }
            VCFIndexLine line = new VCFIndexLine();

            line.setVcfPath(runWithLogging(() -> r.get(FILE_COLUMN).trim(), r, FILE_COLUMN));
            line.setContig(
                runWithLogging(
                    () -> r.get(CHROMOSOME_COLUMN).trim().contentEquals("ALL") ? null : r.get(CHROMOSOME_COLUMN).trim(), r,
                    CHROMOSOME_COLUMN
                )
            );

            line.setAnnotated(runWithLogging(() -> Integer.parseInt(r.get(ANNOTATED_FLAG_COLUMN).trim()) == 1, r, ANNOTATED_FLAG_COLUMN));
            line.setGzipped(runWithLogging(() -> Integer.parseInt(r.get(GZIP_FLAG_COLUMN).trim()) == 1, r, GZIP_FLAG_COLUMN));
            line.setSampleIds(
                runWithLogging(
                    () -> Arrays.stream(r.get(SAMPLE_IDS_COLUMN).split(",")).map(String::trim).toList().toArray(new String[0]), r,
                    SAMPLE_IDS_COLUMN
                )
            );
            line.setPatientIds(
                runWithLogging(
                    () -> Arrays.stream(r.get(PATIENT_IDS_COLUMN).split(",")).map(String::trim).map(Integer::parseInt).toList()
                        .toArray(new Integer[0]),
                    r, PATIENT_IDS_COLUMN
                )
            );
            vcfSet.add(line);
            lineNumber++;
        }
        return new ArrayList<>(vcfSet);
    }

    private <T> T runWithLogging(Supplier<T> supplier, CSVRecord r, int columnNumber) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "Exception parsing vcfIndexFile on line " + r.getRecordNumber() + ", column " + (columnNumber + 1) + ". Value = \""
                    + r.get(columnNumber) + "\"",
                e
            );
        }
    }
}
