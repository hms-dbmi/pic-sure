package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CategoricalMetadata(
    @JsonProperty("columnmeta_is_stigmatized") String columnmetaIsStigmatized, @JsonProperty("columnmeta_name") String columnmetaName,
    @JsonProperty("description") String description, @JsonProperty("columnmeta_min") String columnmetaMin,
    @JsonProperty("HPDS_PATH") String hpdsPath, @JsonProperty("derived_group_id") String derivedGroupId,
    @JsonProperty("columnmeta_hpds_path") String columnmetaHpdsPath, @JsonProperty("columnmeta_var_id") String columnmetaVarId,
    @JsonProperty("columnmeta_var_group_description") String columnmetaVarGroupDescription,
    @JsonProperty("derived_var_description") String derivedVarDescription,
    @JsonProperty("derived_variable_level_data") String derivedVariableLevelData, @JsonProperty("data_hierarchy") String dataHierarchy,
    @JsonProperty("derived_group_description") String derivedGroupDescription, @JsonProperty("columnmeta_max") String columnmetaMax,
    @JsonProperty("columnmeta_description") String columnmetaDescription, @JsonProperty("derived_study_id") String derivedStudyId,
    @JsonProperty("hashed_var_id") String hashedVarId, @JsonProperty("columnmeta_data_type") String columnmetaDataType,
    @JsonProperty("derived_var_id") String derivedVarId, @JsonProperty("columnmeta_study_id") String columnmetaStudyId,
    @JsonProperty("is_stigmatized") String isStigmatized, @JsonProperty("derived_var_name") String derivedVarName,
    @JsonProperty("derived_study_abv_name") String derivedStudyAbvName,
    @JsonProperty("derived_study_description") String derivedStudyDescription,
    @JsonProperty("columnmeta_var_group_id") String columnmetaVarGroupId, @JsonProperty("derived_group_name") String derivedGroupName,
    @JsonProperty("columnmeta_HPDS_PATH") String columnmetaHpdsPathAlternate
) implements Metadata {
}
