package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class DashboardConfig {
    private final Map<String, String> labelDisplayElementPairs;
    private final List<String> columnOrder;

    @Autowired
    public DashboardConfig(
        @Value("#{${dashboard.columns}}") Map<String, String> labelDisplayElementPairs,
        @Value("${dashboard.column-order}") List<String> columnOrder
    ) {
        this.labelDisplayElementPairs = labelDisplayElementPairs;
        this.columnOrder = columnOrder;
    }

    @Bean
    public List<DashboardColumn> getColumns() {
        return labelDisplayElementPairs.entrySet().stream().map(e -> new DashboardColumn(e.getKey(), e.getValue()))
            .sorted((a, b) -> Integer.compare(calculateOrder(a), calculateOrder(b))).toList();
    }

    private int calculateOrder(DashboardColumn column) {
        if (columnOrder.contains(column.dataElement())) {
            return columnOrder.indexOf(column.dataElement());
        } else {
            return Integer.MAX_VALUE;
        }
    }



}
