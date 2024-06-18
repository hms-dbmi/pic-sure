package edu.harvard.dbmi.avillach.dictionary.util;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MapExtractor implements ResultSetExtractor<Map<String, String>> {
    private final String keyName, valueName;

    public MapExtractor(String keyName, String valueName) {
        this.keyName = keyName;
        this.valueName = valueName;
    }

    @Override
    public Map<String, String> extractData(ResultSet rs) throws SQLException, DataAccessException {
        Map<String, String> map = new HashMap<>();
        while (rs.next() && rs.getString(keyName) != null) {
            map.put(rs.getString(keyName), rs.getString(valueName));
        }
        return map;
    }
}
