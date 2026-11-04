package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    description = "Attributes of a continuous concept under the column names the legacy search used. Several keys repeat one value; the repeats are kept because the legacy shape had them."
)
public record ContinuousMetadata(
    @Schema(
        description = "The concept's stigmatized metadata value, true when the variable is stigmatizing. Null when the dictionary holds none. Same value as is_stigmatized.",
        example = "false"
    ) @JsonProperty("columnmeta_is_stigmatized") String columnmetaIsStigmatized,
    @Schema(description = "Display name of the concept. Same value as derived_var_name.", example = "Age") @JsonProperty(
        "columnmeta_name"
    ) String columnmetaName,
    @Schema(
        description = "Description of the variable, empty when the dictionary holds none. Same value as derived_var_description and columnmeta_description.",
        example = "Age of the participant at enrollment, in years"
    ) @JsonProperty("description") String description,
    @Schema(description = "Smallest value recorded for the variable, as decimal text. Same value as min.", example = "18.0") @JsonProperty(
        "columnmeta_min"
    ) String columnmetaMin,
    @Schema(
        description = "Full concept path. Same value as columnmeta_hpds_path and columnmeta_HPDS_PATH.", example = "\\demographics\\AGE\\"
    ) @JsonProperty("HPDS_PATH") String hpdsPath,
    @Schema(
        description = "Name of the concept's parent concept, or All Variables when it has none. Same value as derived_group_description and columnmeta_var_group_id.",
        example = "demographics"
    ) @JsonProperty("derived_group_id") String derivedGroupId,
    @Schema(description = "Full concept path. Same value as HPDS_PATH.", example = "\\demographics\\AGE\\") @JsonProperty(
        "columnmeta_hpds_path"
    ) String columnmetaHpdsPath,
    @Schema(
        description = "Name of the concept, the last segment of its path. Same value as derived_var_id.", example = "AGE"
    ) @JsonProperty("columnmeta_var_id") String columnmetaVarId,
    @Schema(
        description = "Display name of the concept's parent concept. Null when it has none. Same value as derived_group_name.",
        example = "Demographics"
    ) @JsonProperty("columnmeta_var_group_description") String columnmetaVarGroupDescription,
    @Schema(
        description = "Description of the variable. Same value as description.", example = "Age of the participant at enrollment, in years"
    ) @JsonProperty("derived_var_description") String derivedVarDescription,
    @Schema(description = "Always the text of an empty JSON object. Kept for the legacy shape.", example = "{}") @JsonProperty(
        "derived_variable_level_data"
    ) String derivedVariableLevelData,
    @Schema(description = "Always empty. Kept for the legacy shape.") @JsonProperty("data_hierarchy") String dataHierarchy,
    @Schema(
        description = "Name of the concept's parent concept, or All Variables when it has none. Same value as derived_group_id.",
        example = "demographics"
    ) @JsonProperty("derived_group_description") String derivedGroupDescription,
    @Schema(description = "Largest value recorded for the variable, as decimal text. Same value as max.", example = "89.0") @JsonProperty(
        "columnmeta_max"
    ) String columnmetaMax,
    @Schema(
        description = "Description of the variable. Same value as description.", example = "Age of the participant at enrollment, in years"
    ) @JsonProperty("columnmeta_description") String columnmetaDescription,
    @Schema(
        description = "Ref of the dataset the concept belongs to. Same value as columnmeta_study_id.", example = "phs000007"
    ) @JsonProperty("derived_study_id") String derivedStudyId,
    @Schema(
        description = "SHA-256 of the concept path, as 64 lowercase hex characters.",
        example = "f2759dcdd5f8da663519de559fc665177ad5edb0e5c56bbbf03b218d03455903"
    ) @JsonProperty("hashed_var_id") String hashedVarId,
    @Schema(description = "The concept's type as stored in the dictionary.", example = "continuous") @JsonProperty(
        "columnmeta_data_type"
    ) String columnmetaDataType,
    @Schema(
        description = "Name of the concept, the last segment of its path. Same value as columnmeta_var_id.", example = "AGE"
    ) @JsonProperty("derived_var_id") String derivedVarId,
    @Schema(
        description = "Ref of the dataset the concept belongs to. Same value as derived_study_id.", example = "phs000007"
    ) @JsonProperty("columnmeta_study_id") String columnmetaStudyId,
    @Schema(
        description = "The concept's stigmatized metadata value. Same value as columnmeta_is_stigmatized.", example = "false"
    ) @JsonProperty("is_stigmatized") String isStigmatized,
    @Schema(description = "Display name of the concept. Same value as columnmeta_name.", example = "Age") @JsonProperty(
        "derived_var_name"
    ) String derivedVarName,
    @Schema(description = "Abbreviation of the dataset the concept belongs to.", example = "FHS") @JsonProperty(
        "derived_study_abv_name"
    ) String derivedStudyAbvName,
    @Schema(
        description = "Full name of the dataset the concept belongs to, empty when it has none. Despite the key, this is not the dataset's description.",
        example = "Framingham Cohort"
    ) @JsonProperty("derived_study_description") String derivedStudyDescription,
    @Schema(
        description = "Name of the concept's parent concept, or All Variables when it has none. Same value as derived_group_id.",
        example = "demographics"
    ) @JsonProperty("columnmeta_var_group_id") String columnmetaVarGroupId,
    @Schema(
        description = "Display name of the concept's parent concept. Null when it has none. Same value as columnmeta_var_group_description.",
        example = "Demographics"
    ) @JsonProperty("derived_group_name") String derivedGroupName,
    @Schema(description = "Full concept path. Same value as HPDS_PATH.", example = "\\demographics\\AGE\\") @JsonProperty(
        "columnmeta_HPDS_PATH"
    ) String columnmetaHpdsPathAlternate,
    @Schema(
        description = "Smallest value recorded for the variable, as decimal text. Same value as columnmeta_min.", example = "18.0"
    ) @JsonProperty("min") String min,
    @Schema(
        description = "Largest value recorded for the variable, as decimal text. Same value as columnmeta_max.", example = "89.0"
    ) @JsonProperty("max") String max
) implements Metadata {
}
