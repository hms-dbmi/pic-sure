package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class DashboardDrawerRepository {

    private final NamedParameterJdbcTemplate template;

    @Autowired
    public DashboardDrawerRepository(NamedParameterJdbcTemplate template) {
        this.template = template;
    }

    public Optional<List<DashboardDrawer>> getDashboardDrawerRows() {

        String sql = """
            SELECT d.dataset_id,
                MAX(d.full_name) study_fullname,
                MAX(d.abbreviation) study_abbreviation,
                ARRAY_AGG(DISTINCT c.description) consent_groups,
                MAX(d.description) study_summary,
                ARRAY_AGG(DISTINCT dm.value) FILTER (where dm.key IN ('study_focus')) study_focus,
                MAX(DISTINCT dm.value) FILTER (where dm.key IN ('study_design')) study_design,
                MAX(DISTINCT dm.value) FILTER (where dm.key IN ('sponsor')) sponsor
            FROM dataset d
            JOIN dataset_meta dm ON d.dataset_id = dm.dataset_id
            LEFT JOIN consent c ON d.dataset_id = c.dataset_id
            GROUP BY d.dataset_id
            """;

        return Optional.of(template.query(sql, new DashboardDrawerRowMapper()));

    }

    public Optional<DashboardDrawer> getDashboardDrawerRowsByDatasetId(Integer datasetId) {
        String sql = """
            SELECT d.dataset_id dataset_id,
                MAX(d.full_name) study_fullname,
                MAX(d.abbreviation) study_abbreviation,
                ARRAY_AGG(DISTINCT c.description) consent_groups,
                MAX(d.description) study_summary,
                ARRAY_AGG(DISTINCT dm.value) FILTER (where dm.key IN ('study_focus')) study_focus,
                MAX(DISTINCT dm.value) FILTER (where dm.key IN ('study_design')) study_design,
                MAX(DISTINCT dm.value) FILTER (where dm.key IN ('sponsor')) sponsor
            FROM dataset d
            JOIN dataset_meta dm ON d.dataset_id = dm.dataset_id
            JOIN consent c ON d.dataset_id = c.dataset_id
            where d.dataset_id = :datasetId
            GROUP BY d.dataset_id
            """;
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("datasetId", datasetId);

        return template.query(sql, params, new DashboardDrawerRowMapper()).stream().findFirst();
    }
}
