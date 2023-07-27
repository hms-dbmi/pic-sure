package edu.harvard.dbmi.avillach.data.request;

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    description = "Request to add or update a named dataset.",
    example = "{\n" + //
        "    \"queryId\": \"ec780aeb-d981-432a-b72b-51d4ecb3fd53\",\n" + //
        "    \"name\": \"My first Query\",\n" + //
        "    \"archived\": false\n" + //
        "}"
)
public class NamedDatasetRequest {
    @NotNull
    private UUID queryId;

    @NotNull
    @Pattern(regexp = "^[\\w\\d \\-\\\\/?+=\\[\\].():\"']+$")
    private String name;

    private Boolean archived = false;

    public UUID getQueryId() {
        return queryId;
    }

    public void setQueryId(UUID query) {
        this.queryId = query;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getArchived(){
        return archived;
    }
    
    public void setArchived(Boolean archived) {
        this.archived = archived;
    }
}
