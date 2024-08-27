package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Component
public class ConceptMetaExtractor implements ResultSetExtractor<Map<Concept, Map<String, String>>> {

    @Override
    public Map<Concept, Map<String, String>> extractData(ResultSet rs) throws SQLException, DataAccessException {
        Map<Concept, Map<String, String>> sets = new HashMap<>();
        while (rs.next()) {
            Concept key = new ConceptShell(rs.getString("concept_path"), rs.getString("dataset_name"));
            Map<String, String> meta = sets.getOrDefault(key, new HashMap<>());
            meta.put(rs.getString("KEY"), rs.getString("VALUE"));
            sets.put(key, meta);
        }
        return sets;
    }
}
