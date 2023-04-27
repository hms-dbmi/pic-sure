package edu.harvard.dbmi.avillach.domain;

import lombok.Value;

import java.util.List;

@Value
public class PaginatedSearchResult<T> {
    List<T> results;
    Integer page;
    Integer total;
}
