package edu.harvard.dbmi.avillach.dictionary.dataset;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.util.MapExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class DatasetRepository {
    private final NamedParameterJdbcTemplate template;
    private final DatasetMapper mapper;
    private final MapExtractor metaExtractor = new MapExtractor("key", "value");

    @Autowired
    public DatasetRepository(NamedParameterJdbcTemplate template, DatasetMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    public Optional<Dataset> getDataset(String ref) {
        String sql = """
            SELECT
                ref, full_name, abbreviation, description
            FROM
                dataset
            WHERE
                dataset.REF = :ref
            """;

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ref", ref);

        return template.query(sql, params, mapper).stream().findAny();
    }

    public Map<String, String> getDatasetMeta(String ref) {
        String sql = """
            SELECT
                key, value
            FROM
                dataset_meta
                LEFT JOIN dataset ON dataset_meta.dataset_id = dataset.dataset_id
            WHERE
                dataset.REF = :ref
            """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ref", ref);
        return template.query(sql, params, metaExtractor);
    }
}
