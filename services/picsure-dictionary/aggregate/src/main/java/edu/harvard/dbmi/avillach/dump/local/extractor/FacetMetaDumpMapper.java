package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.FacetMetaDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class FacetMetaDumpMapper implements RowMapper<FacetMetaDump> {
    @Override
    public FacetMetaDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FacetMetaDump(rs.getString("FACET"), rs.getString("FACET_CATEGORY"), rs.getString("KEY"), rs.getString("VALUE"));
    }
}
