package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class ConceptResultSetUtil {

    private static final Logger log = LoggerFactory.getLogger(ConceptResultSetUtil.class);
    private final JsonBlobParser jsonBlobParser;

    @Autowired
    public ConceptResultSetUtil(JsonBlobParser jsonBlobParser) {
        this.jsonBlobParser = jsonBlobParser;
    }

    public CategoricalConcept mapCategorical(ResultSet rs) throws SQLException {
        return new CategoricalConcept(
            rs.getString("concept_path"), rs.getString("name"), rs.getString("display"), rs.getString("dataset"),
            rs.getString("description"), rs.getString("values") == null ? List.of() : jsonBlobParser.parseValues(rs.getString("values")),
            rs.getBoolean("allowFiltering"), rs.getString("studyAcronym"), null, null
        );
    }

    public ContinuousConcept mapContinuous(ResultSet rs) throws SQLException {
        return new ContinuousConcept(
            rs.getString("concept_path"), rs.getString("name"), rs.getString("display"), rs.getString("dataset"),
            rs.getString("description"), rs.getBoolean("allowFiltering"), jsonBlobParser.parseMin(rs.getString("values")),
            jsonBlobParser.parseMax(rs.getString("values")), rs.getString("studyAcronym"), null
        );
    }



}
