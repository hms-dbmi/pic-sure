package edu.harvard.dbmi.avillach.domain;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, defaultImpl = GeneralQueryRequest.class)
@JsonSubTypes({ @JsonSubTypes.Type(GeneralQueryRequest.class), @JsonSubTypes.Type(FederatedQueryRequest.class) })
@JsonIgnoreProperties(ignoreUnknown = true)
public interface QueryRequest {
    public Map<String, String> getResourceCredentials();
    public QueryRequest setResourceCredentials(Map<String, String> resourceCredentials);

    public Object getQuery();
    public QueryRequest setQuery(Object query);

    public UUID getResourceUUID();
    public void setResourceUUID(UUID resourceUUID);

    public QueryRequest copy();
}
