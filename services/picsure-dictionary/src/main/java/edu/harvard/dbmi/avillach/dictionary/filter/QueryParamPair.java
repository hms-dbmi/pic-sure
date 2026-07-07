package edu.harvard.dbmi.avillach.dictionary.filter;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public record QueryParamPair(String query, MapSqlParameterSource params) {
}
