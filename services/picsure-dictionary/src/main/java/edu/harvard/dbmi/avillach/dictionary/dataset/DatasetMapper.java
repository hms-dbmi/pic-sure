package edu.harvard.dbmi.avillach.dictionary.dataset;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class DatasetMapper implements RowMapper<Dataset> {
    @Override
    public Dataset mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Dataset(rs.getString("ref"), rs.getString("full_name"), rs.getString("abbreviation"), rs.getString("description"));
    }
}
