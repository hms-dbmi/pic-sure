package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ConceptResultSetUtil {

    private static final Logger log = LoggerFactory.getLogger(ConceptResultSetUtil.class);

    public CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            rs.getString("values") == null ? List.of() : parseValues(rs.getString("values")),
            rs.getBoolean("allowFiltering"), rs.getString("studyAcronym"),
            null,
            null
        );
    }

    public ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"),
            rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            rs.getBoolean("allowFiltering"),
            parseMin(rs.getString("values")), parseMax(rs.getString("values")),
            rs.getString("studyAcronym"),
            null
        );
    }

    public List<String> parseValues(String valuesArr) {
        try {
            ArrayList<String> vals = new ArrayList<>();
            JSONArray arr = new JSONArray(valuesArr);
            for (int i = 0; i < arr.length(); i++) {
                vals.add(arr.getString(i));
            }
            return vals;
        } catch (JSONException ex) {
            return List.of();
        }
    }

    public Float parseMin(String valuesArr) {
        return parseFromIndex(valuesArr, 0);
    }

    private Float parseFromIndex(String valuesArr, int index) {
        try {
            JSONArray arr = new JSONArray(valuesArr);
            if (arr.length() != 2) {
                return 0F;
            }
            String raw = arr.getString(index);
            if (raw.contains("e")) {
                // scientific notation
                return Double.valueOf(raw).floatValue();
            } else {
                return Float.parseFloat(raw);
            }
        } catch (JSONException ex) {
            log.warn("Invalid json array for values: ", ex);
            return 0F;
        } catch (NumberFormatException ex) {
            log.warn("Valid json array but invalid val within: ", ex);
            return 0F;
        }
    }

    public Float parseMax(String valuesArr) {
        return parseFromIndex(valuesArr, 1);
    }
}
