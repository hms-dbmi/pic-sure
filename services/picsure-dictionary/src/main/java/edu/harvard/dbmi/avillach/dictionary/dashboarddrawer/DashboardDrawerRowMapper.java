package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Array; // For handling SQL Array
import java.util.Arrays;
import java.util.List;

public class DashboardDrawerRowMapper implements RowMapper<DashboardDrawer> {

    @Override
    public DashboardDrawer mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DashboardDrawer(
            rs.getInt("dataset_id"), rs.getString("study_fullname"), rs.getString("study_abbreviation"),
            convertSqlArrayToList(rs.getArray("consent_groups")), rs.getString("study_summary"),
            convertSqlArrayToList(rs.getArray("study_focus")), rs.getString("study_design"), rs.getString("sponsor")
        );
    }

    private List<String> convertSqlArrayToList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        } else {
            Object[] arrayContents = (Object[]) sqlArray.getArray();
            // Check if the array contains a single empty value
            if (arrayContents.length == 1 && "".equals(arrayContents[0])) {
                return List.of();
            } else {
                return Arrays.asList((String[]) sqlArray.getArray());
            }
        }
    }
}
