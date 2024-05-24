package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class ConceptRowMapper implements RowMapper<Concept> {

    @Override
    public Concept mapRow(ResultSet rs, int rowNum) throws SQLException {
        return switch (ConceptType.valueOf(rs.getString("concept_type"))) {
            case Categorical -> mapCategorical(rs);
            case Continuous -> mapContinuous(rs);
        };
    }

    private CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"),
            List.of(rs.getString("values").split(",")),
            null,
            null
        );
    }

    private ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"),
            rs.getInt("min"), rs.getInt("max"),
            null
        );
    }
}
