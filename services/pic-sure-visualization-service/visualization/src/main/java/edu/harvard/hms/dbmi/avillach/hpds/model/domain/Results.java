package edu.harvard.hms.dbmi.avillach.hpds.model.domain;

import lombok.Data;

import java.util.Map;

@Data
public class Results {
    Map<String, SearchResult> phenotypes;
}
