package edu.harvard.dbmi.avillach.dictionary.dashboard;

import java.util.List;
import java.util.Map;

public record Dashboard(List<DashboardColumn> columns, List<Map<String, String>> rows) {
}
