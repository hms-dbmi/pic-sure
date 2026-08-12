package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;

public record OutputColumn(SummaryColumnMeta columnMeta, int columnOffset) {
}
