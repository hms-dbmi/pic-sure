package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Component
public class ConceptResultSetExtractor implements ResultSetExtractor<Concept> {
    @Autowired
    private ConceptResultSetUtil conceptResultSetUtil;

    private record ConceptWithId(Concept c, int id) {
    };

    @Override
    public Concept extractData(ResultSet rs) throws SQLException, DataAccessException {
        Map<Integer, List<ConceptWithId>> conceptsByParentId = new HashMap<>();
        ConceptWithId root = null;
        while (rs.next()) {
            Concept c = switch (ConceptType.toConcept(rs.getString("concept_type"))) {
                case Categorical -> conceptResultSetUtil.mapCategorical(rs);
                case Continuous -> conceptResultSetUtil.mapContinuous(rs);
            };
            ConceptWithId conceptWithId = new ConceptWithId(c, rs.getInt("concept_node_id"));
            if (root == null) {
                root = conceptWithId;
            }

            int parentId = rs.getInt("parent_id");
            // weirdness: null value for int is 0, so to check for missing parent value, you need the wasNull check
            if (!rs.wasNull()) {
                List<ConceptWithId> concepts = conceptsByParentId.getOrDefault(parentId, new ArrayList<>());
                concepts.add(conceptWithId);
                conceptsByParentId.put(parentId, concepts);
            }
        }


        return root == null ? null : seedChildren(root, conceptsByParentId);
    }

    private Concept seedChildren(ConceptWithId root, Map<Integer, List<ConceptWithId>> conceptsByParentId) {
        List<Concept> children = conceptsByParentId.getOrDefault(root.id, List.of()).stream()
            .map(conceptWithId -> seedChildren(conceptWithId, conceptsByParentId)).toList();
        return root.c.withChildren(children);
    }
}
