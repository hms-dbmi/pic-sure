package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.ResultType;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Controller
@ConditionalOnProperty(name = "production", havingValue = "true")
public class DataUploadController {

    private static final Logger LOG = LoggerFactory.getLogger(DataUploadController.class);

    @Autowired
    private DataUploadService uploadService;

    @Autowired
    private StatusService statusService;

    @Value("${aws.s3.institution:}")
    private List<String> institutions;

    @PostMapping("/upload/{site}")
    public ResponseEntity<DataUploadStatuses> startUpload(@RequestBody Query query, @PathVariable String site) {
        site = site.toLowerCase();
        query.setExpectedResultType(ResultType.DATAFRAME_TIMESERIES);
        if (!institutions.contains(site)) {
            LOG.info("Could not find site {} in list of sites: {}", site, institutions);
            return ResponseEntity.notFound().build();
        }
        Optional<DataUploadStatuses> maybeStatus = statusService.getStatus(query.getPicSureId());
        // 404 if query not in DB yet.
        if (maybeStatus.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DataUploadStatuses statuses = maybeStatus.get();
        // 403 if query not approved, or was approved on a future date (please don't do that)
        if (statuses.approved() == null || statuses.approved().isAfter(LocalDate.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(statuses);
        }
        // no-op if already uploading
        if (statuses.phenotypic() == UploadStatus.Uploading || statuses.genomic() == UploadStatus.Uploading) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(statuses);
        }
        return ResponseEntity.ok(uploadService.upload(query, site));
    }
}
