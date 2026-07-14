package edu.harvard.hms.dbmi.avillach.hpds.service.filesharing;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileSystemServiceTest {

    @Test
    public void shouldWriteToFile() throws IOException {
        Path dir = Files.createTempDirectory("my-upload-dir");
        FileSystemService subject = new FileSystemService();
        ReflectionTestUtils.setField(subject, "sharingRoot", dir);
        ReflectionTestUtils.setField(subject, "enableFileSharing", true);
        String fileContent = "I just got an ad that tried to sell a baguette with moz, dressing, " +
            "and tomatoes as a healthy lunch, and that's just so far from the truth that it's bugging me. " +
            "Like, come on. It's bread and cheese and oil. I don't care how fresh the tomatoes are.";

        boolean actual = subject.writeResultToFile("out.tsv", fileContent, "my-id");
        String actualContent = Files.readString(dir.resolve("my-id/out.tsv"));

        assertTrue(actual);
        assertEquals(fileContent, actualContent);
    }

    @Test
    public void shouldNotWriteToFile() throws IOException {
        Path dir = Files.createTempDirectory("my-upload-dir");
        FileSystemService subject = new FileSystemService();
        ReflectionTestUtils.setField(subject, "sharingRoot", dir);
        ReflectionTestUtils.setField(subject, "enableFileSharing", false);

        boolean actual = subject.writeResultToFile("out.tsv", "", "my-id");

        assertFalse(actual);
    }
}