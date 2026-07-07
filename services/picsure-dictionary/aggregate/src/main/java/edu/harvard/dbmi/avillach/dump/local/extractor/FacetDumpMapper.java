package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.FacetDump;
import edu.harvard.dbmi.avillach.dump.entities.FacetMetaDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class FacetDumpMapper implements RowMapper<FacetDump> {
    @Override
    public FacetDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FacetDump(
            rs.getString("NAME"), rs.getString("DISPLAY"), rs.getString("DESCRIPTION"), rs.getString("FACET_CATEGORY_NAME"),
            rs.getInt("FACET_ID"), rs.getInt("PARENT_ID")
        );
    }
}
