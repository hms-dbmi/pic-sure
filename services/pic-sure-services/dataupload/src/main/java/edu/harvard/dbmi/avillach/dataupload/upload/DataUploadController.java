package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.hpds.Query;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
@ConditionalOnProperty(name = "production", havingValue = "true")
public class DataUploadController {

    @Autowired
    DataUploadService uploadService;

    @PostMapping("/upload/{site}")
    public ResponseEntity<DataUploadStatuses> startUpload(@RequestBody Query query, @PathVariable String site) {
        uploadService.upload(query, site);
        return ResponseEntity.ok(
            new DataUploadStatuses(UploadStatus.InProgress, UploadStatus.InProgress, query.getId())
        );
    }
}
