package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import java.nio.file.Path;

public record IngestStatus(Path file, long lineCount, int conceptCount, long duration) {
}
