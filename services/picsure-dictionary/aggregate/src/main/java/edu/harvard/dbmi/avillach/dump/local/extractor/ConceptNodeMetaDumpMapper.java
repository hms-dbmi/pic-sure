package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeMetaDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ConceptNodeMetaDumpMapper implements RowMapper<ConceptNodeMetaDump> {
    @Override
    public ConceptNodeMetaDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConceptNodeMetaDump(rs.getString("CONCEPT_PATH"), rs.getString("KEY"), rs.getString("VALUE"));
    }
}
