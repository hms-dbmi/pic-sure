package edu.harvard.dbmi.avillach.dictionary.concept;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportMetadata;
import edu.harvard.dbmi.avillach.dictionary.util.JsonBlobParser;

/**
 * Maps one row of the export metadata query to a {@link ConceptExportMetadata}, keeping each metadata key as stored.
 */
@Component
public class ConceptExportMetadataRowMapper implements RowMapper<ConceptExportMetadata> {

    private final JsonBlobParser jsonBlobParser;

    /**
     * Creates the mapper.
     *
     * @param jsonBlobParser parser for the aggregated metadata column
     */
    public ConceptExportMetadataRowMapper(JsonBlobParser jsonBlobParser) {
        this.jsonBlobParser = jsonBlobParser;
    }

    /**
     * Maps the current row.
     *
     * @param rs the result set, positioned on a row with {@code concept_path}, {@code display}, {@code dataset}, {@code description} and a
     *        {@code metadata} column holding a JSON array of key and value objects
     * @param rowNum the number of the current row
     * @return the row as export metadata
     * @throws SQLException if a column cannot be read
     */
    @Override
    public ConceptExportMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConceptExportMetadata(
            rs.getString("concept_path"), rs.getString("display"), rs.getString("dataset"), rs.getString("description"),
            jsonBlobParser.parseRawMetaData(rs.getString("metadata"))
        );
    }
}
