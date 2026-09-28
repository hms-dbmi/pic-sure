package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the Dockerfile rules to this repository. It reads the files git tracks under the reactor root,
 * so it needs no compiled classes and runs apart from {@link ApiConventionsTest}.
 */
class DockerfileConventionsTest {

    @Test
    void everyExternalBaseImageIsPinnedByDigest() throws IOException {
        Path root = Path.of(System.getProperty("reactor.root"));
        List<String> dockerfiles = DockerfileRules.trackedDockerfiles(root);
        assertFalse(dockerfiles.isEmpty(), () -> "base-images-pinned found no tracked Dockerfile under " + root.toAbsolutePath().normalize());

        List<String> violations = new ArrayList<>();
        for (String dockerfile : dockerfiles) {
            Path file = root.resolve(dockerfile);
            if (Files.isRegularFile(file)) {
                violations.addAll(DockerfileRules.baseImagesArePinned(dockerfile, Files.readString(file)));
            }
        }

        assertTrue(
            violations.isEmpty(),
            () -> "base-images-pinned failed with " + violations.size() + " violation(s):\n  " + String.join("\n  ", violations)
        );
    }
}
