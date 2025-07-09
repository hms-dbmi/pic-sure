package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import org.springframework.lang.NonNull;

import java.util.List;

public record GenomicFilter(String key, List<String> values, Float min, Float max) {}
