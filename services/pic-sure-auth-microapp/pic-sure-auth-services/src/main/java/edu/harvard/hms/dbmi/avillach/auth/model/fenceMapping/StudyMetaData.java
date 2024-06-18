package edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public class StudyMetaData {

    @JsonProperty("study_identifier")
    private String studyIdentifier;

    @JsonProperty("study_type")
    private String studyType;

    @JsonProperty("abbreviated_name")
    private String abbreviatedName;

    @JsonProperty("full_study_name")
    private String fullStudyName;

    @JsonProperty("consent_group_code")
    private String consentGroupCode;

    @JsonProperty("consent_group_name_abv")
    private String consentGroupNameAbv;

    @JsonProperty("consent_group_name")
    private String consentGroupName;

    @JsonProperty("request_access")
    private String requestAccess;

    @JsonProperty("data_type")
    private String dataType;

    @JsonProperty("clinical_variable_count")
    private int clinicalVariableCount;

    @JsonProperty("genetic_sample_size")
    private int geneticSampleSize;

    @JsonProperty("clinical_sample_size")
    private int clinicalSampleSize;

    @JsonProperty("raw_clinical_variable_count")
    private int rawClinicalVariableCount;

    @JsonProperty("raw_genetic_sample_size")
    private int rawGeneticSampleSize;

    @JsonProperty("raw_clinical_sample_size")
    private int rawClinicalSampleSize;

    @JsonProperty("study_version")
    private String studyVersion;

    @JsonProperty("study_phase")
    private String studyPhase;

    @JsonProperty("top_level_path")
    private String topLevelPath;

    @JsonProperty("is_harmonized")
    @JsonDeserialize(using = IsHarmonizedDeserializer.class)
    private Boolean isHarmonized;

    @JsonProperty("study_focus")
    private String studyFocus;

    @JsonProperty("study_design")
    private String studyDesign;

    @JsonProperty("authZ")
    private String authZ;

    @JsonProperty("additional_information")
    private String additionalInformation;

    public String getStudyIdentifier() {
        return studyIdentifier;
    }

    public void setStudyIdentifier(String studyIdentifier) {
        this.studyIdentifier = studyIdentifier;
    }

    public String getStudyType() {
        return studyType;
    }

    public void setStudyType(String studyType) {
        this.studyType = studyType;
    }

    public String getAbbreviatedName() {
        return abbreviatedName;
    }

    public void setAbbreviatedName(String abbreviatedName) {
        this.abbreviatedName = abbreviatedName;
    }

    public String getFullStudyName() {
        return fullStudyName;
    }

    public void setFullStudyName(String fullStudyName) {
        this.fullStudyName = fullStudyName;
    }

    public String getConsentGroupCode() {
        return consentGroupCode;
    }

    public void setConsentGroupCode(String consentGroupCode) {
        this.consentGroupCode = consentGroupCode;
    }

    public String getConsentGroupNameAbv() {
        return consentGroupNameAbv;
    }

    public void setConsentGroupNameAbv(String consentGroupNameAbv) {
        this.consentGroupNameAbv = consentGroupNameAbv;
    }

    public String getConsentGroupName() {
        return consentGroupName;
    }

    public void setConsentGroupName(String consentGroupName) {
        this.consentGroupName = consentGroupName;
    }

    public String getRequestAccess() {
        return requestAccess;
    }

    public void setRequestAccess(String requestAccess) {
        this.requestAccess = requestAccess;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public int getClinicalVariableCount() {
        return clinicalVariableCount;
    }

    public void setClinicalVariableCount(int clinicalVariableCount) {
        this.clinicalVariableCount = clinicalVariableCount;
    }

    public int getGeneticSampleSize() {
        return geneticSampleSize;
    }

    public void setGeneticSampleSize(int geneticSampleSize) {
        this.geneticSampleSize = geneticSampleSize;
    }

    public int getClinicalSampleSize() {
        return clinicalSampleSize;
    }

    public void setClinicalSampleSize(int clinicalSampleSize) {
        this.clinicalSampleSize = clinicalSampleSize;
    }

    public int getRawClinicalVariableCount() {
        return rawClinicalVariableCount;
    }

    public void setRawClinicalVariableCount(int rawClinicalVariableCount) {
        this.rawClinicalVariableCount = rawClinicalVariableCount;
    }

    public int getRawGeneticSampleSize() {
        return rawGeneticSampleSize;
    }

    public void setRawGeneticSampleSize(int rawGeneticSampleSize) {
        this.rawGeneticSampleSize = rawGeneticSampleSize;
    }

    public int getRawClinicalSampleSize() {
        return rawClinicalSampleSize;
    }

    public void setRawClinicalSampleSize(int rawClinicalSampleSize) {
        this.rawClinicalSampleSize = rawClinicalSampleSize;
    }

    public String getStudyVersion() {
        return studyVersion;
    }

    public void setStudyVersion(String studyVersion) {
        this.studyVersion = studyVersion;
    }

    public String getStudyPhase() {
        return studyPhase;
    }

    public void setStudyPhase(String studyPhase) {
        this.studyPhase = studyPhase;
    }

    public String getTopLevelPath() {
        return topLevelPath;
    }

    public void setTopLevelPath(String topLevelPath) {
        this.topLevelPath = topLevelPath;
    }

    public Boolean getIsHarmonized() {
        return isHarmonized;
    }

    public void setIsHarmonized(Boolean isHarmonized) {
        this.isHarmonized = isHarmonized;
    }

    public String getStudyFocus() {
        return studyFocus;
    }

    public void setStudyFocus(String studyFocus) {
        this.studyFocus = studyFocus;
    }

    public String getStudyDesign() {
        return studyDesign;
    }

    public void setStudyDesign(String studyDesign) {
        this.studyDesign = studyDesign;
    }

    public String getAuthZ() {
        return authZ;
    }

    public void setAuthZ(String authZ) {
        this.authZ = authZ;
    }

    public String getAdditionalInformation() {
        return additionalInformation;
    }

    public void setAdditionalInformation(String additionalInformation) {
        this.additionalInformation = additionalInformation;
    }
}
