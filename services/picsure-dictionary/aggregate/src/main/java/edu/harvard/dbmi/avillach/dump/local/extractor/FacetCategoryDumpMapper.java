package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.FacetCategoryDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class FacetCategoryDumpMapper implements RowMapper<FacetCategoryDump> {
    @Override
    public FacetCategoryDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FacetCategoryDump(rs.getString("NAME"), rs.getString("DISPLAY"), rs.getString("DESCRIPTION"));
    }
}
