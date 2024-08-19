package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import org.springframework.http.MediaType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CsvWriter implements ResultWriter {

    private final de.siegmar.fastcsv.writer.CsvWriter csvWriter;

    private final FileWriter fileWriter;

    private final File file;

    public CsvWriter(File file) {
        this.file = file;
        csvWriter = new de.siegmar.fastcsv.writer.CsvWriter();
        try {
            this.fileWriter = new FileWriter(file);
        } catch (IOException e) {
            throw new RuntimeException("IOException while appending temp file : " + file.getAbsolutePath(), e);
        }
    }

    @Override
    public void writeHeader(String[] data) {
        try {
            List<String[]> dataList = new ArrayList<>();
            dataList.add(data);
            csvWriter.write(fileWriter, dataList);
        } catch (IOException e) {
            throw new RuntimeException("IOException while appending to CSV file", e);
        }
    }
    @Override
    public void writeEntity(Collection<String[]> data) {
        try {
            csvWriter.write(fileWriter, data);
        } catch (IOException e) {
            throw new RuntimeException("IOException while appending to CSV file", e);
        }
    }

    @Override
    public void writeMultiValueEntity(Collection<List<List<String>>> data) {
        throw new RuntimeException("Method not implemented");
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public MediaType getResponseType() {
        return MediaType.TEXT_PLAIN;
    }

    @Override
    public void close() {
        try {
            fileWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
