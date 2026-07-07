package edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.util.Objects;

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

    public StudyMetaData setStudyIdentifier(String studyIdentifier) {
        this.studyIdentifier = studyIdentifier;
        return this;
    }

    public String getStudyType() {
        return studyType;
    }

    public StudyMetaData setStudyType(String studyType) {
        this.studyType = studyType;
        return this;
    }

    public String getAbbreviatedName() {
        return abbreviatedName;
    }

    public StudyMetaData setAbbreviatedName(String abbreviatedName) {
        this.abbreviatedName = abbreviatedName;
        return this;
    }

    public String getFullStudyName() {
        return fullStudyName;
    }

    public StudyMetaData setFullStudyName(String fullStudyName) {
        this.fullStudyName = fullStudyName;
        return this;
    }

    public String getConsentGroupCode() {
        return consentGroupCode;
    }

    public StudyMetaData setConsentGroupCode(String consentGroupCode) {
        this.consentGroupCode = consentGroupCode;
        return this;
    }

    public String getConsentGroupNameAbv() {
        return consentGroupNameAbv;
    }

    public StudyMetaData setConsentGroupNameAbv(String consentGroupNameAbv) {
        this.consentGroupNameAbv = consentGroupNameAbv;
        return this;
    }

    public String getConsentGroupName() {
        return consentGroupName;
    }

    public StudyMetaData setConsentGroupName(String consentGroupName) {
        this.consentGroupName = consentGroupName;
        return this;
    }

    public String getRequestAccess() {
        return requestAccess;
    }

    public StudyMetaData setRequestAccess(String requestAccess) {
        this.requestAccess = requestAccess;
        return this;
    }

    public String getDataType() {
        return dataType;
    }

    public StudyMetaData setDataType(String dataType) {
        this.dataType = dataType;
        return this;
    }

    public int getClinicalVariableCount() {
        return clinicalVariableCount;
    }

    public StudyMetaData setClinicalVariableCount(int clinicalVariableCount) {
        this.clinicalVariableCount = clinicalVariableCount;
        return this;
    }

    public int getGeneticSampleSize() {
        return geneticSampleSize;
    }

    public StudyMetaData setGeneticSampleSize(int geneticSampleSize) {
        this.geneticSampleSize = geneticSampleSize;
        return this;
    }

    public int getClinicalSampleSize() {
        return clinicalSampleSize;
    }

    public StudyMetaData setClinicalSampleSize(int clinicalSampleSize) {
        this.clinicalSampleSize = clinicalSampleSize;
        return this;
    }

    public int getRawClinicalVariableCount() {
        return rawClinicalVariableCount;
    }

    public StudyMetaData setRawClinicalVariableCount(int rawClinicalVariableCount) {
        this.rawClinicalVariableCount = rawClinicalVariableCount;
        return this;
    }

    public int getRawGeneticSampleSize() {
        return rawGeneticSampleSize;
    }

    public StudyMetaData setRawGeneticSampleSize(int rawGeneticSampleSize) {
        this.rawGeneticSampleSize = rawGeneticSampleSize;
        return this;
    }

    public int getRawClinicalSampleSize() {
        return rawClinicalSampleSize;
    }

    public StudyMetaData setRawClinicalSampleSize(int rawClinicalSampleSize) {
        this.rawClinicalSampleSize = rawClinicalSampleSize;
        return this;
    }

    public String getStudyVersion() {
        return studyVersion;
    }

    public StudyMetaData setStudyVersion(String studyVersion) {
        this.studyVersion = studyVersion;
        return this;
    }

    public String getStudyPhase() {
        return studyPhase;
    }

    public StudyMetaData setStudyPhase(String studyPhase) {
        this.studyPhase = studyPhase;
        return this;
    }

    public String getTopLevelPath() {
        return topLevelPath;
    }

    public StudyMetaData setTopLevelPath(String topLevelPath) {
        this.topLevelPath = topLevelPath;
        return this;
    }

    public Boolean getIsHarmonized() {
        return isHarmonized;
    }

    public StudyMetaData setHarmonized(Boolean harmonized) {
        isHarmonized = harmonized;
        return this;
    }

    public String getStudyFocus() {
        return studyFocus;
    }

    public StudyMetaData setStudyFocus(String studyFocus) {
        this.studyFocus = studyFocus;
        return this;
    }

    public String getStudyDesign() {
        return studyDesign;
    }

    public StudyMetaData setStudyDesign(String studyDesign) {
        this.studyDesign = studyDesign;
        return this;
    }

    public String getAuthZ() {
        return authZ;
    }

    public StudyMetaData setAuthZ(String authZ) {
        this.authZ = authZ;
        return this;
    }

    public String getAdditionalInformation() {
        return additionalInformation;
    }

    public StudyMetaData setAdditionalInformation(String additionalInformation) {
        this.additionalInformation = additionalInformation;
        return this;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append("studyIdentifier", studyIdentifier).append("studyType", studyType)
            .append("abbreviatedName", abbreviatedName).append("fullStudyName", fullStudyName).append("consentGroupCode", consentGroupCode)
            .append("consentGroupNameAbv", consentGroupNameAbv).append("consentGroupName", consentGroupName)
            .append("requestAccess", requestAccess).append("dataType", dataType).append("clinicalVariableCount", clinicalVariableCount)
            .append("geneticSampleSize", geneticSampleSize).append("clinicalSampleSize", clinicalSampleSize)
            .append("rawClinicalVariableCount", rawClinicalVariableCount).append("rawGeneticSampleSize", rawGeneticSampleSize)
            .append("rawClinicalSampleSize", rawClinicalSampleSize).append("studyVersion", studyVersion).append("studyPhase", studyPhase)
            .append("topLevelPath", topLevelPath).append("isHarmonized", isHarmonized).append("studyFocus", studyFocus)
            .append("studyDesign", studyDesign).append("authZ", authZ).append("additionalInformation", additionalInformation).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        StudyMetaData that = (StudyMetaData) o;
        return clinicalVariableCount == that.clinicalVariableCount && geneticSampleSize == that.geneticSampleSize
            && clinicalSampleSize == that.clinicalSampleSize && rawClinicalVariableCount == that.rawClinicalVariableCount
            && rawGeneticSampleSize == that.rawGeneticSampleSize && rawClinicalSampleSize == that.rawClinicalSampleSize
            && Objects.equals(studyIdentifier, that.studyIdentifier) && Objects.equals(studyType, that.studyType)
            && Objects.equals(abbreviatedName, that.abbreviatedName) && Objects.equals(fullStudyName, that.fullStudyName)
            && Objects.equals(consentGroupCode, that.consentGroupCode) && Objects.equals(consentGroupNameAbv, that.consentGroupNameAbv)
            && Objects.equals(consentGroupName, that.consentGroupName) && Objects.equals(requestAccess, that.requestAccess)
            && Objects.equals(dataType, that.dataType) && Objects.equals(studyVersion, that.studyVersion)
            && Objects.equals(studyPhase, that.studyPhase) && Objects.equals(topLevelPath, that.topLevelPath)
            && Objects.equals(isHarmonized, that.isHarmonized) && Objects.equals(studyFocus, that.studyFocus)
            && Objects.equals(studyDesign, that.studyDesign) && Objects.equals(authZ, that.authZ)
            && Objects.equals(additionalInformation, that.additionalInformation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            studyIdentifier, studyType, abbreviatedName, fullStudyName, consentGroupCode, consentGroupNameAbv, consentGroupName,
            requestAccess, dataType, clinicalVariableCount, geneticSampleSize, clinicalSampleSize, rawClinicalVariableCount,
            rawGeneticSampleSize, rawClinicalSampleSize, studyVersion, studyPhase, topLevelPath, isHarmonized, studyFocus, studyDesign,
            authZ, additionalInformation
        );
    }
}
