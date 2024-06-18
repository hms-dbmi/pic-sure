package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class ConceptRowMapper implements RowMapper<Concept> {

    @Override
    public Concept mapRow(ResultSet rs, int rowNum) throws SQLException {
        return switch (ConceptType.toConcept(rs.getString("concept_type"))) {
            case Categorical -> mapCategorical(rs);
            case Continuous -> mapContinuous(rs);
        };
    }

    private CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            rs.getString("values") == null ? List.of() : List.of(rs.getString("values").split(",")),
            null,
            null
        );
    }

    private ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            parseMin(rs.getString("values")), parseMax(rs.getString("values")),
            null
        );
    }

    private Integer parseMin(String valuesArr) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            return arr.length() == 2 ? arr.getInt(0) : 0;
        } catch (JSONException ex) {
            return 0;
        }
    }

    private Integer parseMax(String valuesArr) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            return arr.length() == 2 ? arr.getInt(1) : 0;
        } catch (JSONException ex) {
            return 0;
        }
    }
}
