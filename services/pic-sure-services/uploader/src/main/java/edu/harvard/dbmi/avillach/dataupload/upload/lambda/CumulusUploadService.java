package edu.harvard.dbmi.avillach.dataupload.upload.lambda;

import edu.harvard.dbmi.avillach.dataupload.hpds.HPDSClient;
import edu.harvard.dbmi.avillach.dataupload.hpds.hpdsartifactsdonotchange.Query;
import edu.harvard.dbmi.avillach.dataupload.status.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class CumulusUploadService {
    private static final Logger log = LoggerFactory.getLogger(CumulusUploadService.class);
    private final StatusService statusService;
    private final POSTUrlFetcher urlFetcher;
    private final HPDSClient hpdsClient;
    private final Path sharingRoot;


    @Autowired
    public CumulusUploadService(StatusService statusService, POSTUrlFetcher urlFetcher, HPDSClient hpdsClient, Path sharingRoot) {
        this.statusService = statusService;
        this.urlFetcher = urlFetcher;
        this.hpdsClient = hpdsClient;
        this.sharingRoot = sharingRoot;
    }

    public boolean asyncUpload(Query query) {
        log.info("Async upload called");
        Thread.ofVirtual().start(() -> upload(query));
        return true;
    }
    private void upload(Query query) {
        log.info("Fetching upload URL");
        Optional<String> uploadURL = urlFetcher.getPreSignedUploadURL(query.getPicSureId(), "patients.txt");
        if (uploadURL.isEmpty()) {
            log.error("Could not get upload URL. Exiting");
            return;
        }

        boolean written = hpdsClient.writePatientData(query);
        if (!written) {
            log.warn("HPDS did not write data. Exiting");
            return;
        }
        log.info("HPDS reported successfully writing {} data for {} to file.", "patients.txt", query.getPicSureId());

        Path data = Path.of(sharingRoot.toString(), query.getPicSureId(), "patients.txt");
        if (!Files.exists(data)) {
            log.info("HPDS lied; file {} DNE. Status set to error", data);
            return;
        }

        log.info("File location verified. Uploading for {} to AWS", query.getPicSureId());
        try {
            boolean uploaded = uploadFileToPresignedUrl(uploadURL.get(), data);
        } catch (IOException e) {
            log.error("Error uploading data: ", e);
        }
        log.info("Done uploading patients for query {}", query.getPicSureId());

    }

    private boolean uploadFileToPresignedUrl(String presignedUrlString, Path filePath) throws IOException {

        URL presignedUrl = new URL(presignedUrlString);
        HttpURLConnection connection = (HttpURLConnection) presignedUrl.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        OutputStream out = connection.getOutputStream();

        try (RandomAccessFile file = new RandomAccessFile(filePath.toString(), "r");
            FileChannel inChannel = file.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192); //Buffer size is 8k

            while (inChannel.read(buffer) > 0) {
                buffer.flip();
                for (int i = 0; i < buffer.limit(); i++) {
                    out.write(buffer.get());
                }
                buffer.clear();
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        out.close();
        connection.getResponseCode();
        log.info("HTTP response code is " + connection.getResponseCode());
        return HttpURLConnection.HTTP_OK == connection.getResponseCode();
    }
}
