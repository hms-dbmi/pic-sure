package edu.harvard.dbmi.avillach.dump.local.extractor;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeMetaDump;
import edu.harvard.dbmi.avillach.dump.entities.FacetConceptPair;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class FacetConceptPairMapper implements RowMapper<FacetConceptPair> {
    @Override
    public FacetConceptPair mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FacetConceptPair(rs.getString("FACET"), rs.getString("FACET_CATEGORY"), rs.getString("CONCEPT_PATH"));
    }
}
