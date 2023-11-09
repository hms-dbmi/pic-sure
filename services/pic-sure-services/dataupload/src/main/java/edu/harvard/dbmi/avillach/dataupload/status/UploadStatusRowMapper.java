package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class UploadStatusRowMapper implements RowMapper<UploadStatus> {
    @Override
    public UploadStatus mapRow(ResultSet rs, int rowNum) throws SQLException {
        return UploadStatus.fromString(rs.getString("status"));
    }
}
