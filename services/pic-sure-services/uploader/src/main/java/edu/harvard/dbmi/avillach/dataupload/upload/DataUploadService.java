package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.aws.SelfRefreshingS3Client;
import edu.harvard.dbmi.avillach.dataupload.aws.SiteAWSInfo;
import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Service
public class DataUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(DataUploadService.class);

    @Autowired
    private Semaphore uploadLock;

    @Value("${aws.s3.access_key_id}")
    private String keyId;

    @Autowired
    private SelfRefreshingS3Client s3;

    @Autowired
    private HPDSClient hpds;

    @Autowired
    private StatusService statusService;

    @Autowired
    private Path sharingRoot;

    @Value("${institution.name}")
    private String home;

    @Autowired
    private Map<String, SiteAWSInfo> roleARNs;

    public DataUploadStatuses asyncUpload(Query query, String site, DataType dataType) {
        dataType.getStatusSetter(statusService).accept(query, UploadStatus.Queued);
        Thread.ofVirtual().start(() -> uploadData(query, dataType, site));
        statusService.setSite(query, site);
        return statusService.getStatus(query.getPicSureId())
            .orElse(null); // this should never happen. the status object is created during the set calls above
    }

    protected void uploadData(Query query, DataType dataType, String site) {
        LOG.info("Requesting lock for  {} / {}", dataType, query.getPicSureId());
        try {
            uploadLock.acquire();
        } catch (InterruptedException e) {
            LOG.error("Failed to acquire. Abandoning upload");
            return;
        }

        LOG.info("Starting upload {} process for uuid: {}", dataType, query.getPicSureId());
        BiConsumer<Query, UploadStatus> statusSetter = dataType.getStatusSetter(statusService);
        statusSetter.accept(query, UploadStatus.Querying);
        boolean success = dataType.getHPDSUpload(hpds).apply(query);
        if (!success) {
            statusSetter.accept(query, UploadStatus.Error);
            LOG.info("HPDS failed to write {} data. Status for {} set to error.", dataType, query.getPicSureId());
            uploadLock.release();
            return;
        } else {
            statusSetter.accept(query, UploadStatus.Uploading);
        }
        LOG.info("HPDS reported successfully writing {} data for {} to file.", dataType, query.getPicSureId());

        Path data = Path.of(sharingRoot.toString(), query.getPicSureId(), dataType.fileName);
        if (!Files.exists(data)) {
            statusSetter.accept(query, UploadStatus.Error);
            LOG.info("HPDS lied; file {} DNE. Status set to error", data);
            uploadLock.release();
            return;
        }
        
        LOG.info("File location verified. Uploading for {} to AWS", query.getPicSureId());
        success = uploadFileFromPath(data, roleARNs.get(site), query.getPicSureId());
        deleteFile(data);
        if (success) {
            statusSetter.accept(query, UploadStatus.Uploaded);
            LOG.info("{} data for {} uploaded!", dataType, query.getPicSureId());
        } else {
            statusSetter.accept(query, UploadStatus.Error);
        }
        LOG.info("Releasing lock for  {} / {}", dataType, query.getPicSureId());
        uploadLock.release();
    }

    private static void deleteFile(Path data) {
        try {
            Files.delete(data);
        } catch (IOException e) {
            LOG.error("Could not data {}", data, e);
        }
    }

    private boolean uploadFileFromPath(Path p, SiteAWSInfo site, String dir) {
        try {
            RequestBody body = RequestBody.fromFile(p.toFile());
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(site.bucket())
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(site.kmsKeyID())
                .key(Path.of(dir, home + "_" + p.getFileName().toString()).toString())
                .build();
            s3.getS3Client(site.siteName()).putObject(request, body);
        } catch (AwsServiceException | SdkClientException e) {
            LOG.info("Error uploading file from {} to bucket {}", p, site.bucket(), e);
            return false;
        }
        return true;
    }
}
