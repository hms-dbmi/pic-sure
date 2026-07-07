package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSystemService} and updated to use new
 * Query entity
 */
@Service
public class FileSystemV3Service {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemV3Service.class);

    @Autowired
    private Path sharingRoot;

    @Value("${enable_file_sharing:false}")
    private boolean enableFileSharing;

    public boolean writeResultToFile(String fileName, AsyncResult result, String id) {
        if (Files.exists(result.getTempFilePath())) {
            LOG.info("A temp file already exists for query {}. Moving that rather than rewriting.", id);
            return moveFile(fileName, result.getTempFilePath(), id);
        }
        result.getStream().open();
        return writeStreamToFile(fileName, result.getStream(), id);
    }

    public boolean writeResultToFile(String fileName, String result, String id) {
        return writeStreamToFile(fileName, new ByteArrayInputStream(result.getBytes()), id);
    }


    private boolean moveFile(String destinationName, Path sourceFile, String queryId) {
        if (!enableFileSharing) {
            LOG.warn("Attempted to write query result to file while file sharing is disabled. No-op.");
            return false;
        }

        Path dirPath = Path.of(sharingRoot.toString(), queryId);
        Path filePath = Path.of(sharingRoot.toString(), queryId, destinationName);

        try {
            LOG.info("Moving query {} to file: {}", queryId, filePath);
            makeDirIfDNE(dirPath);
            Path result = Files.copy(sourceFile, filePath, REPLACE_EXISTING);
            // we have to copy and then delete because of how mv works with mounted drives
            // (it doesn't work)
            Files.delete(sourceFile);
            return Files.exists(result);
        } catch (IOException e) {
            LOG.error("Error moving.", e);
            return false;
        }
    }

    private boolean writeStreamToFile(String fileName, InputStream content, String queryId) {
        if (!enableFileSharing) {
            LOG.warn("Attempted to write query result to file while file sharing is disabled. No-op.");
            return false;
        }

        Path dirPath = Path.of(sharingRoot.toString(), queryId);
        Path filePath = Path.of(sharingRoot.toString(), queryId, fileName);

        try {
            LOG.info("Writing query {} to file: {}", queryId, filePath);
            makeDirIfDNE(dirPath);
            return Files.copy(content, filePath) > 0;
        } catch (IOException e) {
            LOG.error("Error writing result.", e);
            return false;
        } finally {
            IOUtils.closeQuietly(content);
        }
    }

    private synchronized void makeDirIfDNE(Path dirPath) throws IOException {
        if (!Files.exists(dirPath)) {
            Files.createDirectory(dirPath);
        }
    }
}
