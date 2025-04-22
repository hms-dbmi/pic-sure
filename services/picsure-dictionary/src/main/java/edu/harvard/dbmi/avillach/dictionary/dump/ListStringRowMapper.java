package edu.harvard.dbmi.avillach.dictionary.dump;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

@Component
public class ListStringRowMapper implements RowMapper<List<String>> {

    private static final Logger log = LoggerFactory.getLogger(ListStringRowMapper.class);

    @Override
    public List<String> mapRow(ResultSet rs, int rowNum) throws SQLException {
        int columnCount = rs.getMetaData().getColumnCount();
        return IntStream.range(1, columnCount + 1).mapToObj(i -> {
            try {
                return rs.getString(i);
            } catch (SQLException e) {
                log.error("Error pulling column: ", e);
                return "";
            }
        }).toList();
    }

}
