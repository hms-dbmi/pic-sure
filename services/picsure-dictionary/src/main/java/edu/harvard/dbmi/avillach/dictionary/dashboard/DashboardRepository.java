package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Repository
public class DashboardRepository {
    private final NamedParameterJdbcTemplate template;
    private final List<DashboardColumn> columns;
    private final Set<String> nonMetaColumns;
    private final DashboardRowResultSetExtractor extractor;

    @Autowired
    public DashboardRepository(
        NamedParameterJdbcTemplate template,
        List<DashboardColumn> columns,
        @Value("${dashboard.nonmeta-columns}")
        Set<String> nonMetaColumns, DashboardRowResultSetExtractor extractor
    ) {
        this.template = template;
        this.columns = columns;
        this.nonMetaColumns = nonMetaColumns;
        this.extractor = extractor;
    }

    public List<Map<String, String>> getRows() {
        String sql = """
            SELECT
                abbreviation, full_name AS name,
                dataset_meta.KEY AS key,
                dataset_meta.VALUE AS value
            FROM
                dataset
                JOIN dataset_meta ON dataset.dataset_id = dataset_meta.dataset_id
            WHERE
                dataset_meta.KEY IN (:keys)
            ORDER BY name ASC, abbreviation ASC
            """;
        List<String> keys = columns.stream()
            .map(DashboardColumn::dataElement)
            .filter(Predicate.not(nonMetaColumns::contains))
            .toList();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("keys", keys);
        return template.query(sql, params, extractor);
    }
}
