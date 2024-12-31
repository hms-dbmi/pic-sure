package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Component
public class DataUploadStatusesMapper implements RowMapper<DataUploadStatuses> {
    @Override
    public DataUploadStatuses mapRow(ResultSet rs, int rowNum) throws SQLException {
        UploadStatus genomic = UploadStatus.fromString(rs.getString("GENOMIC_STATUS"));
        UploadStatus pheno = UploadStatus.fromString(rs.getString("PHENOTYPIC_STATUS"));
        UploadStatus patient = UploadStatus.fromString(rs.getString("PATIENT_STATUS"));
        UploadStatus queryStatus = UploadStatus.fromString(rs.getString("QUERY_JSON_STATUS"));
        String query = fromDashlessString(rs.getString("QUERY")).toString();
        Date approved = rs.getDate("APPROVED");
        String site = rs.getString("SITE");
        return new DataUploadStatuses(
                genomic, pheno, patient, queryStatus, query, approved == null ? null : approved.toLocalDate(), site
        );
    }

    private UUID fromDashlessString(String uuid) {
        String dashed = uuid.replaceFirst(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
            "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(dashed);
    }
}
