package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final DashboardRepository repository;
    private final List<DashboardColumn> columns;

    public DashboardService(DashboardRepository repository, List<DashboardColumn> columns) {
        this.repository = repository;
        this.columns = columns;
    }

    public Dashboard getDashboard() {
        List<Map<String, String>> rows = repository.getRows();
        return new Dashboard(
            columns,
            rows
        );
    }
}
