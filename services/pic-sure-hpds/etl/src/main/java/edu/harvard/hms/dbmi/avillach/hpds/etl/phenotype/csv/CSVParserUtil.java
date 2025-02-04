package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv;

import org.apache.commons.csv.CSVRecord;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Date;
import java.util.stream.Collectors;

public class CSVParserUtil {
    public static final int PATIENT_NUM = 0;
    public static final int CONCEPT_PATH = 1;
    public static final int NUMERIC_VALUE = 2;
    public static final int TEXT_VALUE = 3;
    public static final int DATETIME = 4;
    
    public static String parseConceptPath(CSVRecord record, boolean doVarNameRollup) {
        String conceptPathFromRow = record.get(CONCEPT_PATH);
        conceptPathFromRow = Arrays.stream(conceptPathFromRow.split("\\\\"))
            .map(String::trim)
            .collect(Collectors.joining("\\")) + "\\";
        conceptPathFromRow = stripWeirdUnicodeChars(conceptPathFromRow);

        // \\ufffd = �
        String textValueFromRow = stripWeirdUnicodeChars(trim(record.get(TEXT_VALUE)));
        if (doVarNameRollup && conceptPathFromRow.endsWith("\\" + textValueFromRow + "\\")) {
            // This regex deletes the last node from the concept path, i.e. "rolling it up"
            return conceptPathFromRow.replaceAll("\\\\[^\\\\]*\\\\$", "\\\\");
        } else {
            return conceptPathFromRow;
        }
    }

    private static String stripWeirdUnicodeChars(@Nonnull String raw) {
        return raw.replaceAll("\\ufffd", "");
    }

    public static String trim(@Nullable String maybeString) {
        return maybeString == null ? "" : maybeString.trim();
    }

    public static @Nullable Date parseDate(CSVRecord record) {
        Date date = null;
        try {
            if (record.size() > 4 && record.get(DATETIME) != null && !record.get(DATETIME).isEmpty()) {
                date = new Date(Long.parseLong(record.get(DATETIME)));
            }
        } catch (NumberFormatException e) {
            return null;
        }

        return date;
    }
}
