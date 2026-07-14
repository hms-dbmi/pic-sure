package edu.harvard.dbmi.avillach.dataupload.hpds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class FileSystemConfig {
    @Value("${file_sharing_root:/gic_query_results/}")
    private String fileSharingRootDir;

    @Value("${enable_file_sharing:false}")
    private boolean enableFileSharing;

    @Bean()
    Path sharingRoot() {
        if (!enableFileSharing) {
            return Path.of("/dev/null");
        }

        Path path = Path.of(fileSharingRootDir);
        if (!path.toFile().exists()) {
            throw new RuntimeException(fileSharingRootDir + " DNE.");
        }

        return path;
    }
}
