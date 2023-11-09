package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UploadStatusRepository {
    @Autowired
    private JdbcTemplate template;

    @Autowired
    private UploadStatusRowMapper mapper;

    public Optional<UploadStatus> getGenomicStatus(String queryId) {
        try {
            return Optional.ofNullable(template.queryForObject(
                "SELECT genomic_status AS status FROM query_status WHERE query = unhex(?)",
                mapper,
                queryId.replace("-", "")
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<UploadStatus> getPhenotypicStatus(String queryId) {
        try {
            return Optional.ofNullable(template.queryForObject(
                "SELECT phenotypic_status AS status FROM query_status WHERE query = unhex(?)",
                mapper,
                queryId.replace("-", "")
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void setGenomicStatus(String queryId, UploadStatus status) {
        String sql = """
            INSERT INTO query_status
                (query, genomic_status)
                VALUES (unhex(?), ?)
                ON DUPLICATE KEY UPDATE genomic_status=?
        """;
        template.update(sql, queryId.replace("-", ""), status.toString(), status.toString());
    }

    public void setPhenotypicStatus(String queryId, UploadStatus status) {
        String sql = """
            INSERT INTO query_status
                (query, phenotypic_status)
                VALUES (unhex(?), ?)
                ON DUPLICATE KEY UPDATE phenotypic_status=?
        """;
        template.update(sql, queryId.replace("-", ""), status.toString(), status.toString());
    }
}
