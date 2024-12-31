package edu.harvard.dbmi.avillach.dataupload.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dataupload.aws.AWSClientBuilder;
import edu.harvard.dbmi.avillach.dataupload.aws.SiteAWSInfo;
import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.DataUploadStatuses;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Service
public class DataUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(DataUploadService.class);
    private static final int SIXTEEN_MB = 16 * 1024 * 1024;

    @Autowired
    private Semaphore uploadLock;

    @Value("${aws.s3.access_key_id}")
    private String keyId;

    @Value("${institution.name}")
    private String home;

    @Autowired
    private AWSClientBuilder s3ClientBuilder;

    @Autowired
    private HPDSClient hpds;

    @Autowired
    private StatusService statusService;

    @Autowired
    private Path sharingRoot;

    @Autowired
    private Map<String, SiteAWSInfo> roleARNs;

    private static final ObjectMapper mapper = new ObjectMapper();

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
        uploadQueryJson(query, roleARNs.get(site));
        LOG.info("Releasing lock for  {} / {}", dataType, query.getPicSureId());
        uploadLock.release();
    }

    private void uploadQueryJson(Query query, SiteAWSInfo site) {
        UploadStatus queryUploadStatus = statusService.getStatus(query.getPicSureId())
                                             .map(DataUploadStatuses::query)
                                             .orElse(UploadStatus.Unsent);
        if (queryUploadStatus == UploadStatus.Uploaded || queryUploadStatus == UploadStatus.Uploading) {
            return;
        }
        statusService.setQueryUploadStatus(query, UploadStatus.Uploading);
        LOG.info("Uploading query json for {}", query.getPicSureId());
        try {
            String queryJson = mapper.writeValueAsString(query);
            LOG.info("Created query JSON. Writing to file.");
            Path jsonPath = Path.of(sharingRoot.toString(), query.getPicSureId(), "query.json");
            Files.writeString(jsonPath, queryJson);
            if (!uploadFileFromPath(jsonPath, site, query.getPicSureId())) {
                LOG.info("Failed to write query.json");
                statusService.setQueryUploadStatus(query, UploadStatus.Error);
            }
            Files.delete(jsonPath);
        } catch (JsonProcessingException e) {
            statusService.setQueryUploadStatus(query, UploadStatus.Error);
            LOG.info("Failed to get query json: ", e);
        } catch (IOException e) {
            statusService.setQueryUploadStatus(query, UploadStatus.Error);
            LOG.info("Failed to write query json: ", e);
        }
        LOG.info("Successfully uploaded query.json for {} to {}", query.getPicSureId(), site.siteName());
        statusService.setQueryUploadStatus(query, UploadStatus.Uploaded);
    }

    private void deleteFile(Path data) {
        try {
            Files.delete(data);
        } catch (IOException e) {
            LOG.error("Could not data {}", data, e);
        }
    }

    private boolean uploadFileFromPath(Path p, SiteAWSInfo site, String dir) {
        Optional<S3Client> maybeClient = s3ClientBuilder.buildClientForSite(site.siteName());
        if (maybeClient.isEmpty()) {
            LOG.info("There is no client for site {}", site);
            return false;
        }
        S3Client s3 = maybeClient.get();
        LOG.info("Starting multipart upload for file {} to site {} in dir {}", p, site, dir);

        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
            .bucket(site.bucket())
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(site.kmsKeyID())
            .key(Path.of(dir, home + "_" + p.getFileName().toString()).toString())
            .build();
        String uploadId;
        try {
            uploadId = s3.createMultipartUpload(createRequest).uploadId();
        } catch (AwsServiceException e) {
            LOG.error("Error creating multipart: ", e);
            return false;
        }
        LOG.info("Created initial multipart request and notified S3");

        LOG.info("Starting upload process...");
        List<CompletedPart> completedParts = uploadAllParts(p, site, dir, uploadId, s3);
        if (completedParts.isEmpty()) {
            return false;
        }
        LOG.info("Upload complete! Uploaded {} parts", completedParts.size());

        LOG.info("Notifying S3 of completed upload...");
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
            .parts(completedParts)
            .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
            .bucket(site.bucket())
            .key(Path.of(dir, home + "_" + p.getFileName().toString()).toString())
            .uploadId(uploadId)
            .multipartUpload(completedUpload)
            .build();

        try {
            s3.completeMultipartUpload(completeRequest);
        } catch (AwsServiceException | SdkClientException e) {
            LOG.error("Error finishing multipart: ", e);
            return false;
        }
        LOG.info("Done uploading {} to {}", p.getFileName(), site.siteName());
        return true;
    }

    private List<CompletedPart> uploadAllParts(Path p, SiteAWSInfo site, String dir, String uploadId, S3Client s3) {
        List<CompletedPart> completedParts = new ArrayList<>();
        int part = 1;
        ByteBuffer buffer = ByteBuffer.allocate(SIXTEEN_MB);

        try (RandomAccessFile file = new RandomAccessFile(p.toString(), "r")) {
            long fileSize = file.length();
            long position = 0;

            while (position < fileSize) {
                file.seek(position);
                int bytesRead = file.getChannel().read(buffer);

                LOG.info("Uploading file {} part {}", p.getFileName(), part);
                buffer.flip();
                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(site.bucket())
                    .key(Path.of(dir, home + "_" + p.getFileName().toString()).toString())
                    .uploadId(uploadId)
                    .partNumber(part)
                    .contentLength((long) bytesRead)
                    .build();


                UploadPartResponse response = s3.uploadPart(uploadPartRequest, RequestBody.fromByteBuffer(buffer));

                completedParts.add(CompletedPart.builder()
                    .partNumber(part)
                    .eTag(response.eTag())
                    .build());

                buffer.clear();
                position += bytesRead;
                part++;
            }
        } catch (IOException | AwsServiceException | SdkClientException e) {
            LOG.error("Failed to upload file {}, part {}: ", p.getFileName(), part, e);
            return List.of();
        }
        LOG.info("Uploaded all parts, finishing");
        return completedParts;
    }
}
