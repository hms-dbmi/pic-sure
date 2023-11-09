package edu.harvard.dbmi.avillach.dataupload.status;

import edu.harvard.dbmi.avillach.dataupload.hpds.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UploadStatusService {
    @Autowired
    private UploadStatusRepository repository;

    public void setGenomicStatus(Query query, UploadStatus status) {
        repository.setGenomicStatus(query.getId(), status);
    }

    public void setPhenotypicStatus(Query query, UploadStatus status) {
        repository.setPhenotypicStatus(query.getId(), status);
    }

    public Optional<DataUploadStatuses> getStatus(String queryId) {
        Optional<UploadStatus> genomicStatus = repository.getGenomicStatus(queryId);
        Optional<UploadStatus> phenotypicStatus = repository.getPhenotypicStatus(queryId);
        if (genomicStatus.isPresent() && phenotypicStatus.isPresent()) {
            return Optional.of(new DataUploadStatuses(genomicStatus.get(), phenotypicStatus.get(), queryId));
        } else {
            return Optional.empty();
        }
    }

}
