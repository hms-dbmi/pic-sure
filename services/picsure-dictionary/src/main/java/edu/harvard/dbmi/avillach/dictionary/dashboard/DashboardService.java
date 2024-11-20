package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final DashboardRepository repository;
    private final List<DashboardColumn> columns;
    private final boolean bdcHack;

    @Autowired
    public DashboardService(
        DashboardRepository repository, List<DashboardColumn> columns, @Value("${dashboard.enable.bdc_hack}") boolean bdcHack
    ) {
        this.repository = repository;
        this.columns = columns;
        this.bdcHack = bdcHack;
    }

    public Dashboard getDashboard() {
        if (bdcHack) {
            List<Map<String, String>> rows = repository.getHackyBDCRows();
            return new Dashboard(hackyBDCColumns, rows);
        }
        List<Map<String, String>> rows = repository.getRows();
        return new Dashboard(columns, rows);
    }

    private static final List<DashboardColumn> hackyBDCColumns = List.of(
        new DashboardColumn("abbreviation", "Abbreviation"), new DashboardColumn("name", "Name"),
        new DashboardColumn("study_focus", "Study Focus"), new DashboardColumn("program_name", "Program"),
        new DashboardColumn("participants", "Participants"), new DashboardColumn("clinvars", "Clinical Variables"),
        new DashboardColumn("samples", "Samples Sequenced"), new DashboardColumn("accession", "Accession"),
        new DashboardColumn("additional_info_link", "Study Link")
    );
}
