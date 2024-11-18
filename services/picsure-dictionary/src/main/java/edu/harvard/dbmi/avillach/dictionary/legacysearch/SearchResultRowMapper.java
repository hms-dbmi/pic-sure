package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptType;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Result;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class SearchResultRowMapper implements RowMapper<SearchResult> {

    private final MetadataResultSetUtil metadataResultSetUtil;

    @Autowired
    public SearchResultRowMapper(MetadataResultSetUtil metadataResultSetUtil) {
        this.metadataResultSetUtil = metadataResultSetUtil;
    }

    @Override
    public SearchResult mapRow(ResultSet rs, int rowNum) throws SQLException {
        return mapSearchResults(rs);
    }

    private SearchResult mapSearchResults(ResultSet rs) throws SQLException {
        Result result = switch (ConceptType.toConcept(rs.getString("conceptType"))) {
            case Categorical -> this.metadataResultSetUtil.mapCategoricalMetadata(rs);
            case Continuous -> this.metadataResultSetUtil.mapContinuousMetadata(rs);
        };

        return new SearchResult(result);
    }


}
