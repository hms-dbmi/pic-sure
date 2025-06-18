package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import java.util.List;

public record PhenotypicFilter(PhenotypicFilterType phenotypicFilterType, String conceptPath, List<String> values, Double min, Double max, Boolean not) implements PhenotypicClause {
}
