package edu.harvard.dbmi.avillach.dictionary.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DashboardRowResultSetExtractor implements ResultSetExtractor<List<Map<String, String>>> {
    // This is a template of all the configured columns.
    // It's used to ensure that empty values exist for all cells,
    // even if there is no matching val in the database.
    private final Map<String, String> template;

    @Autowired
    public DashboardRowResultSetExtractor(List<DashboardColumn> columns) {
        template = columns.stream()
            .collect(Collectors.toMap(DashboardColumn::label, (ignored) -> ""));
    }

    @Override
    public List<Map<String, String>> extractData(ResultSet rs) throws SQLException, DataAccessException {
        String currentName = "";
        String currentAbbreviation = "";
        boolean beforeStart = true;
        Map<String, String> row = new HashMap<>(template);
        List<Map<String, String>> rows = new ArrayList<>();
        while (rs.next()) {
            String abbreviation = rs.getString("abbreviation");
            String name = rs.getString("name");
            if (beforeStart) {
                currentName = name;
                currentAbbreviation = abbreviation;
                beforeStart = false;
            }
            // start of new row
            if (!currentAbbreviation.equals(abbreviation) || !currentName.equals(name)) {
                // finish up the old row by adding non-meta fields
                row.put("abbreviation", currentAbbreviation);
                row.put("name", currentName);
                rows.add(row);
                // start new row
                currentName = name;
                currentAbbreviation = abbreviation;
                row = new HashMap<>(template);
            }
            row.put(rs.getString("key"), rs.getString("value"));
        }
        // add the last row to the response only if there has been at least one set in the result set
        if (!beforeStart) {
            row.put("abbreviation", currentAbbreviation);
            row.put("name", currentName);
            rows.add(row);
        }
        return rows;
    }
}
