package edu.harvard.dbmi.avillach.dataupload.upload;

import edu.harvard.dbmi.avillach.dataupload.aws.SelfRefreshingS3Client;
import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.Query;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatus;
import edu.harvard.dbmi.avillach.dataupload.status.UploadStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

@ConditionalOnProperty(name = "production", havingValue = "true")
@Service
public class DataUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(DataUploadService.class);

    @Value("${aws.s3.home_institute_bucket}")
    private String bucketName;

    @Value("${aws.s3.access_key_id}")
    private String keyId;

    @Value("${aws.kms.key_id}")
    private String kmsKeyId;

    @Autowired
    private SelfRefreshingS3Client s3;

    @Autowired
    private HPDSClient hpds;

    @Autowired
    private UploadStatusService statusService;

    @Autowired
    private Path sharingRoot;

    public void upload(Query query, String site) {
        Thread.ofVirtual().start(() -> uploadData(query, DataType.Phenotypic, site));
        Thread.ofVirtual().start(() -> uploadData(query, DataType.Genomic, site));
    }
    
    private enum DataType {Genomic("genomic_data.tsv"), Phenotypic("phenotypic_data.tsv");
        private final String fileName;
        
        DataType(String fileName) {
            this.fileName = fileName;    
        }
    }

    private void uploadData(Query query, DataType dataType, String site) {
        LOG.info("Starting upload {} process for uuid: {}", dataType, query.getId());
        BiConsumer<Query, UploadStatus> statusSetter = 
            dataType == DataType.Genomic ? statusService::setGenomicStatus : statusService::setPhenotypicStatus;

        boolean success = dataType == DataType.Genomic ? hpds.writeGenomicData(query) : hpds.writePhenotypicData(query);
        if (!success) {
            statusSetter.accept(query, UploadStatus.Error);
            LOG.info("HPDS failed to write {} data. Status for {} set to error.", dataType, query.getId());
            return;
        }
        LOG.info("HPDS reported successfully writing {} data for {} to file.", dataType, query.getId());

        Path data = Path.of(sharingRoot.toString(), query.getId().toString(), dataType.fileName);
        if (!Files.exists(data)) {
            statusSetter.accept(query, UploadStatus.Error);
            LOG.info("HPDS lied; file {} DNE. Status set to error", data);
            return;
        }
        
        LOG.info("File location verified. Uploading for {} to AWS", query.getId());
        success = uploadFileFromPath(data, site);
        if (success) {
            statusSetter.accept(query, UploadStatus.Complete);
            LOG.info("{} data for {} uploaded!", dataType, query.getId());
        } else {
            statusSetter.accept(query, UploadStatus.Error);
        }
    }

    private boolean uploadFileFromPath(Path p, String site) {
        try {
            RequestBody body = RequestBody.fromFile(p.toFile());
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(kmsKeyId)
                .key(p.getFileName().toString())
                .build();
            s3.getS3Client(site).putObject(request, body);
        } catch (AwsServiceException | SdkClientException e) {
            LOG.info("Error uploading file from {} to bucket {}", p, bucketName, e);
            return false;
        }
        return true;
    }
}
