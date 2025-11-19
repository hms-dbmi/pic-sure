package edu.harvard.dbmi.avillach.data.request;

import javax.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(
    description = "Request to add or update a configuration.",
    example = "{\n" + "    \"uuid\": \"076d4f2a-cfe8-486e-a7f9-b938086f3e1e\",\n" + "    \"name\": \"FEATURE_FLAG_X\",\n"
        + "    \"kind\": \"ui\",\n" + "    \"value\": \"true\",\n" + "    \"markForDelete\": falseq,\n"
        + "    \"description\": \"This configuration controls feature X\"\n" + "}"
)
public class ConfigurationRequest {
    private UUID uuid;

    @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$")
    private String name;

    @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$")
    private String kind;

    private String value;

    private String description;

    private Boolean markForDelete;

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return this.kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getMarkForDelete() {
        return this.markForDelete;
    }

    public void setMarkForDelete(Boolean markForDelete) {
        this.markForDelete = markForDelete;
    }
}
