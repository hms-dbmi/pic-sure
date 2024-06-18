package edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class BioDataCatalyst {

    @JsonProperty("bio_data_catalyst")
    private List<StudyMetaData> studyMetaData;

    public List<StudyMetaData> getStudyMetaData() {
        return studyMetaData;
    }

    public void setStudyMetaData(List<StudyMetaData> studyMetaData) {
        this.studyMetaData = studyMetaData;
    }
}
