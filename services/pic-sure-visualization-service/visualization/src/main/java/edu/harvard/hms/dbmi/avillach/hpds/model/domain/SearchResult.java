package edu.harvard.hms.dbmi.avillach.hpds.model.domain;

import lombok.Data;

@Data
public class SearchResult {
    String name;
    boolean categorical;
    String[] categoryValues;
    int observationCount;
    int patientCount;
}
