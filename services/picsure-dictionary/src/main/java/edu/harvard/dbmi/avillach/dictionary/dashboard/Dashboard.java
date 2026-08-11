package edu.harvard.dbmi.avillach.dictionary.dashboard;


import java.util.List;
import java.util.Map;

/**
 * The rows stay untyped on purpose: which columns exist is deployment configuration ({@code dashboard.columns}), so a row's keys are the
 * configured {@link DashboardColumn#dataElement()} values and cannot be modelled as fields.
 */
public record Dashboard(List<DashboardColumn> columns, List<Map<String, String>> rows) {
}
