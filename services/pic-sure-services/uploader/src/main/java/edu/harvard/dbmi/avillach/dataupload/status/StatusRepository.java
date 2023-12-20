package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public class StatusRepository {
    @Autowired
    private JdbcTemplate template;

    @Autowired
    private DataUploadStatusesMapper statusesMapper;

    public Optional<DataUploadStatuses> getQueryStatus(String queryId) {
        String sql = """
                SELECT
                    GENOMIC_STATUS, PHENOTYPIC_STATUS, hex(QUERY) as QUERY, APPROVED, SITE
                FROM
                    query_status
                WHERE
                    QUERY = unhex(?)
                """;
        return template.query(sql, statusesMapper, queryId.replace("-", ""))
                .stream()
                .findFirst();
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

    public void setApproved(String queryId, LocalDate approvalDate) {
        String sql = """
            INSERT INTO query_status
                (QUERY, APPROVED)
                VALUES (unhex(?), ?)
                ON DUPLICATE KEY UPDATE APPROVED=?
        """;
        template.update(sql, queryId.replace("-", ""), approvalDate, approvalDate);
    }

    public void setSite(String picSureId, String site) {
        String sql = """
            INSERT INTO query_status
                (QUERY, SITE)
                VALUES (unhex(?), ?)
                ON DUPLICATE KEY UPDATE SITE=?
        """;
        template.update(sql, picSureId.replace("-", ""), site, site);
    }
}
