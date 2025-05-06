package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.FacetCategoryMetaDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class FacetCategoryMetaDumpMapper implements RowMapper<FacetCategoryMetaDump> {
    @Override
    public FacetCategoryMetaDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FacetCategoryMetaDump(rs.getString("NAME"), rs.getString("KEY"), rs.getString("VALUE"));
    }
}
