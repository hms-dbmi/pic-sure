package edu.harvard.dbmi.avillach.contracts.info;


import java.util.List;
import java.util.UUID;

public record ResourceInfo(UUID id, String name, List<QueryFormat> queryFormats) {

    public ResourceInfo {
        queryFormats = queryFormats == null ? List.of() : queryFormats;
    }
}
