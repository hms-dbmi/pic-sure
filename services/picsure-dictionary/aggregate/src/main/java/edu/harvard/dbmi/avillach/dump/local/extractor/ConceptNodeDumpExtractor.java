package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeDump;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ConceptNodeDumpExtractor implements RowMapper<ConceptNodeDump> {
    @Override
    public ConceptNodeDump mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConceptNodeDump(
            rs.getString("REF"), rs.getString("NAME"), rs.getString("DISPLAY"), rs.getString("CONCEPT_TYPE"), rs.getString("CONCEPT_PATH"),
            rs.getInt("CONCEPT_NODE_ID"), rs.getInt("PARENT_ID")
        );
    }
}
