package edu.harvard.dbmi.avillach.dataupload.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class StatusController {

    @Autowired
    ClientStatusService statusService;

    @Autowired
    UploadStatusService uploadStatusService;

    @GetMapping("/status/server")
    public ResponseEntity<String> getServerStatus() {
        return ResponseEntity.ok(statusService.getClientStatus());
    }

    @GetMapping("/status/{queryId}")
    public ResponseEntity<DataUploadStatuses> getUploadStatus(@PathVariable("queryId") String queryId) {
        return uploadStatusService.getStatus(queryId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
