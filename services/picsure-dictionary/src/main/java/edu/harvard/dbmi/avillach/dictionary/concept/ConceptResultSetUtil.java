package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class ConceptResultSetUtil {

    public CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            rs.getString("values") == null ? List.of() : List.of(rs.getString("values").split(",")),
            null,
            null
        );
    }

    public ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            parseMin(rs.getString("values")), parseMax(rs.getString("values")),
            null
        );
    }

    public Integer parseMin(String valuesArr) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            return arr.length() == 2 ? arr.getInt(0) : 0;
        } catch (JSONException ex) {
            return 0;
        }
    }

    public Integer parseMax(String valuesArr) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            return arr.length() == 2 ? arr.getInt(1) : 0;
        } catch (JSONException ex) {
            return 0;
        }
    }
}
