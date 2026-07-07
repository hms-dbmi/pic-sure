package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ConceptRowWithMetaMapper implements RowMapper<Concept> {

    private final ConceptResultSetUtil conceptResultSetUtil;

    @Autowired
    public ConceptRowWithMetaMapper(ConceptResultSetUtil conceptResultSetUtil) {
        this.conceptResultSetUtil = conceptResultSetUtil;
    }

    @Override
    public Concept mapRow(ResultSet rs, int rowNum) throws SQLException {
        return switch (ConceptType.toConcept(rs.getString("concept_type"))) {
            case Categorical -> conceptResultSetUtil.mapCategoricalWithMetadata(rs);
            case Continuous -> conceptResultSetUtil.mapContinuousWithMetadata(rs);
        };
    }

}
