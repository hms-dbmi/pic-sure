package edu.harvard.dbmi.avillach.dictionary.util;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class MapExtractorWithPrettyKeys implements ResultSetExtractor<Map<String, String>> {
    private final String keyName, valueName;

    public MapExtractorWithPrettyKeys(String keyName, String valueName) {
        this.keyName = keyName;
        this.valueName = valueName;
    }


    @Override
    public Map<String, String> extractData(ResultSet rs) throws SQLException, DataAccessException {
        Map<String, String> map = new HashMap<>();
        while (rs.next()) {
            String rawKey = rs.getString(keyName);
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            String prettyKey = Arrays.stream(rawKey.split("_")).filter(token -> !token.isBlank())
                .map(token -> token.substring(0, 1).toUpperCase() + token.substring(1).toLowerCase()).collect(Collectors.joining(" "));
            String previous = map.putIfAbsent(prettyKey, rs.getString(valueName));
            if (previous != null) {
                throw new IllegalStateException("Duplicate pretty metadata key: " + prettyKey);
            }
        }
        return map;
    }

}
