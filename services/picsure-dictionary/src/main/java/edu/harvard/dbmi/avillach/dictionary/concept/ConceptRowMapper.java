package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class ConceptRowMapper implements RowMapper<Concept> {

    @Autowired
    ConceptResultSetUtil conceptResultSetUtil;

    @Override
    public Concept mapRow(ResultSet rs, int rowNum) throws SQLException {
        return switch (ConceptType.toConcept(rs.getString("concept_type"))) {
            case Categorical -> conceptResultSetUtil.mapCategorical(rs);
            case Continuous -> conceptResultSetUtil.mapContinuous(rs);
        };
    }
}
