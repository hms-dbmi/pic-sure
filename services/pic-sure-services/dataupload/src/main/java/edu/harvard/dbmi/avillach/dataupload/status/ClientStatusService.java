package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class ClientStatusService {
    private final AtomicReference<String> clientStatus = new AtomicReference<>("uninitialized");

    public String getClientStatus() {
        return clientStatus.get();
    }

    public void setClientStatus(String clientStatus) {
        this.clientStatus.set(clientStatus);
    }
}
