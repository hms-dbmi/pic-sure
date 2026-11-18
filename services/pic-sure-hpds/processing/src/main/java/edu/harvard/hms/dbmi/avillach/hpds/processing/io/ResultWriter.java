package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import org.springframework.http.MediaType;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface ResultWriter {
    void writeHeader(String[] data);

    void writeEntity(Collection<String[]> data);
    void writeMultiValueEntity(Collection<List<List<String>>> data);

    File getFile();

    MediaType getResponseType();

    void close();

    /**
     * Releases a writer whose result will never be read. Unlike {@link #close()}, the file it leaves behind does not have to be complete.
     * The default closes the writer.
     */
    default void abandon() {
        close();
    }
}
