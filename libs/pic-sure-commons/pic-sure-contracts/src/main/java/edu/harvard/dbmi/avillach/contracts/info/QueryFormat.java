package edu.harvard.dbmi.avillach.contracts.info;


import java.util.List;
import java.util.Map;

public record QueryFormat(String name, String description, Map<String, Object> specification, List<Map<String, Object>> examples) {
}
