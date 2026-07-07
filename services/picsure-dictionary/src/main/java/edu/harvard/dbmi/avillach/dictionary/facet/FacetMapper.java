package edu.harvard.dbmi.avillach.dictionary.facet;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class FacetMapper implements RowMapper<Facet> {
    @Override
    public Facet mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Facet(
            rs.getString("name"), rs.getString("display"), rs.getString("description"), rs.getString("full_name"), null, List.of(),
            rs.getString("category"), null
        );
    }
}
