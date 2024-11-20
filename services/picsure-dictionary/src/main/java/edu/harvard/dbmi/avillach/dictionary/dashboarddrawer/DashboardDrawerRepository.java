package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class DashboardDrawerRepository {

    private final NamedParameterJdbcTemplate template;

    private static final Logger log = LoggerFactory.getLogger(DashboardDrawerRepository.class);

    @Autowired
    public DashboardDrawerRepository(NamedParameterJdbcTemplate template) {
        this.template = template;
    }

    public List<DashboardDrawer> getDashboardDrawerRows() {
        String materializedViewSql = """
            select * from dictionary_db.dict.dataset_meta_materialized_view dmmv;
            """;

        String fallbackSql = """
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
            JOIN consent c ON d.dataset_id = c.dataset_id
            GROUP BY d.dataset_id
            """;

        try {
            return template.query(materializedViewSql, new DashboardDrawerRowMapper());
        } catch (Exception e) {
            log.debug("Materialized view not available, using fallback query. Error: {}", e.getMessage());
            return template.query(fallbackSql, new DashboardDrawerRowMapper());
        }
    }

    public Optional<DashboardDrawer> getDashboardDrawerRows(Integer datasetId) {
        String materializedViewSql = """
            select * from dictionary_db.dict.dataset_meta_materialized_view dmmv where dmmv.dataset_id = :datasetId;
            """;

        String fallbackSql = """
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

        try {
            return template.query(materializedViewSql, params, new DashboardDrawerRowMapper()).stream().findFirst();
        } catch (Exception e) {
            log.debug("Materialized view not available, using fallback query. Error: {}", e.getMessage());
            return template.query(fallbackSql, params, new DashboardDrawerRowMapper()).stream().findFirst();
        }
    }
}
