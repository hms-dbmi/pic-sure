package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;

import java.util.function.BiConsumer;
import java.util.function.Function;

public enum DataType {
    Genomic("genomic_data.tsv"), Phenotypic("phenotypic_data.csv"), Patient("patients.txt");
    public final String fileName;

    DataType(String fileName) {
        this.fileName = fileName;
    }

    public BiConsumer<Query, UploadStatus> getStatusSetter(StatusService statusService) {
        return switch (this) {
            case Genomic -> statusService::setGenomicStatus;
            case Phenotypic -> statusService::setPhenotypicStatus;
            case Patient -> statusService::setPatientStatus;
        };
    }

    public Function<Query, Boolean> getHPDSUpload(HPDSClient client) {
        return switch (this) {
            case Genomic -> client::writeGenomicData;
            case Phenotypic -> client::writePhenotypicData;
            case Patient -> client::writePatientData;
        };
    }
}