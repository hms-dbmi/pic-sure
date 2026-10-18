package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writes {@code columnMeta.csv} exactly like {@code LoadingStore.dumpStatsAndColumnMeta}, the file the dictionary ETL reads: eleven
 * columns, every value as {@code String.valueOf} (so a missing min or max is the text {@code null}), the category values joined with
 * {@code µ}, and {@code CSVFormat.DEFAULT}. The 14 column variant in {@code ColumnMetaBuilder.writeColumnMetaCsv} is a different format and
 * is not what the legacy consumers expect.
 *
 * <p>Columns: 0 name, 1 widthInBytes, 2 columnOffset, 3 isCategorical, 4 category values, 5 min, 6 max, 7 allObservationsOffset, 8
 * allObservationsLength, 9 observationCount, 10 patientCount.
 */
public final class LegacyColumnMetaCsv {

    private LegacyColumnMetaCsv() {}

    /** @param metadata the concepts in the order they are written (the store passes a TreeMap, as the legacy loader's metastore) */
    public static void write(Map<String, ColumnMeta> metadata, Path csvPath) throws IOException {
        try (
            BufferedWriter writer = Files.newBufferedWriter(
                csvPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            ); CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)
        ) {
            for (ColumnMeta columnMeta : metadata.values()) {
                Object[] out = new Object[11];
                StringBuilder categories = new StringBuilder();
                if (columnMeta.getCategoryValues() != null && !columnMeta.getCategoryValues().isEmpty()) {
                    int last = columnMeta.getCategoryValues().size() - 1;
                    int index = 0;
                    for (String value : columnMeta.getCategoryValues()) {
                        categories.append(value);
                        if (index++ != last) {
                            categories.append("µ");
                        }
                    }
                }
                out[0] = columnMeta.getName();
                out[1] = String.valueOf(columnMeta.getWidthInBytes());
                out[2] = String.valueOf(columnMeta.getColumnOffset());
                out[3] = String.valueOf(columnMeta.isCategorical());
                out[4] = categories;
                out[5] = String.valueOf(columnMeta.getMin());
                out[6] = String.valueOf(columnMeta.getMax());
                out[7] = String.valueOf(columnMeta.getAllObservationsOffset());
                out[8] = String.valueOf(columnMeta.getAllObservationsLength());
                out[9] = String.valueOf(columnMeta.getObservationCount());
                out[10] = String.valueOf(columnMeta.getPatientCount());
                printer.printRecord(out);
            }
        }
    }
}
