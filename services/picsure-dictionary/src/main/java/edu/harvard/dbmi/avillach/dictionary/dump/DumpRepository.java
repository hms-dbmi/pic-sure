package edu.harvard.dbmi.avillach.dictionary.dump;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DumpRepository {
    private final NamedParameterJdbcTemplate template;
    private final ListStringRowMapper rowMapper;
    private static final String DUMP_TEMPLATE = """
        SELECT *
        FROM %s
        ORDER BY %s_id ASC
        LIMIT :limit
        OFFSET :offset
        """;

    @Autowired
    public DumpRepository(NamedParameterJdbcTemplate template, ListStringRowMapper rowMapper) {
        this.template = template;
        this.rowMapper = rowMapper;
    }

    public List<List<String>> getRowsForTable(Pageable page, DumpTable table) {
        String query = DUMP_TEMPLATE.formatted(table.getSqlName(), table.getSqlName());
        MapSqlParameterSource params =
            new MapSqlParameterSource().addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        return template.query(query, params, rowMapper);
    }
}
