package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(
    name = "QueryRequest",
    description = "Object containing the query object under 'query'." + " The query object expectedResultType can be on of the following "
        + "\"COUNT\", \"CROSS_COUNT\", \"INFO_COLUMN_LISTING\", \"OBSERVATION_COUNT\", \"OBSERVATION_CROSS_COUNT\", \"DATAFRAME\". ",
    example = "{\n" + "    \"resourceUUID\": \"<RESOURCE UUID>\",\n" + "    \"query\": {\n" + "        \"select\": [],\n"
        + "        \"phenotypicClause\": {\n" + "            \"operator\": \"AND\",\n" + "            \"phenotypicClauses\": [\n"
        + "                {\n" + "                    \"phenotypicFilterType\": \"FILTER\",\n"
        + "                    \"conceptPath\": \"\\\\demographics\\\\SEX\\\\\",\n"
        + "                    \"values\": [\"female\", \"male\"]\n" + "                },\n" + "                {\n"
        + "                    \"phenotypicFilterType\": \"FILTER\",\n"
        + "                    \"conceptPath\": \"\\\\demographics\\\\AGE\\\\\",\n" + "                    \"min\": 0,\n"
        + "                    \"max\": 85\n" + "                }\n" + "            ]\n" + "        },\n"
        + "        \"genomicFilters\": [],\n" + "        \"expectedResultType\": \"COUNT\"\n" + "    }\n" + "}"
)

/*
 * QueryRequests for vanilla PIC-SURE
 */
public class GeneralQueryRequest implements QueryRequest {

    @Schema(hidden = true)
    private Object query;

    @Schema(hidden = true)
    private UUID resourceUUID;

    @Override
    public Object getQuery() {
        return query;
    }

    @Override
    public GeneralQueryRequest setQuery(Object query) {
        this.query = query;
        return this;
    }

    @Override
    public UUID getResourceUUID() {
        return resourceUUID;
    }

    @Override
    public void setResourceUUID(UUID resourceUUID) {
        this.resourceUUID = resourceUUID;
    }

    @Override
    public GeneralQueryRequest copy() {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setQuery(getQuery());
        request.setResourceUUID(getResourceUUID());

        return request;
    }
}
