package edu.harvard.dbmi.avillach.dataupload.status;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StatusService {
    private final AtomicReference<String> clientStatus = new AtomicReference<>("uninitialized");

    @Autowired
    private StatusRepository repository;


    public String getClientStatus() {
        return clientStatus.get();
    }

    public void setClientStatus(String clientStatus) {
        this.clientStatus.set(clientStatus);
    }

    public void setGenomicStatus(Query query, UploadStatus status) {
        repository.setGenomicStatus(query.getPicSureId(), status);
    }

    public void setPhenotypicStatus(Query query, UploadStatus status) {
        repository.setPhenotypicStatus(query.getPicSureId(), status);
    }

    public Optional<DataUploadStatuses> getStatus(String queryId) {
        return repository.getQueryStatus(queryId);
    }

    public Optional<DataUploadStatuses> approve(String queryId, LocalDate approvalDate) {
        repository.setApproved(queryId, approvalDate);
        return repository.getQueryStatus(queryId);
    }

    public void setSite(Query query, String site) {
        repository.setSite(query.getPicSureId(), site);
    }
}
