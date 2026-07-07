package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import com.google.common.base.Joiner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class VCFIndexBuilder {

    private final Set<String> validPatientTypes;


    private final File vcfPatientMappingFile;
    private final File patientUUIDToIdMappingFile;
    private final File vcfIndexOutputDirectory;
    private Map<String, List<String>> fileToPatientListMap;
    private Map<String, String> patientUUIDToPatientIdMapping;

    private static final String VCF_INDEX_DIRECTORY = "/opt/local/hpds";

    private static final Joiner COMMA_JOINER = Joiner.on(",");

    public VCFIndexBuilder(File vcfPatientMappingFile, File patientUUIDToIdMappingFile, File vcfIndexOutputDirectory, Set<String> validPatientType) {
        this.vcfPatientMappingFile = vcfPatientMappingFile;
        this.patientUUIDToIdMappingFile = patientUUIDToIdMappingFile;
        this.vcfIndexOutputDirectory = vcfIndexOutputDirectory;
        this.validPatientTypes = validPatientType;
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("There must be 4 parameters to VCFIndexBuilder");
        }
        File vcfIndexOutputDirectoryFile = new File(args[2]);
        if (!vcfIndexOutputDirectoryFile.isDirectory()) {
            throw new IllegalArgumentException("Argument 3 must be a valid directory");
        }
        new VCFIndexBuilder(
                new File(args[0]),
                new File(args[1]),
                vcfIndexOutputDirectoryFile,
                Set.of(args[3].split(","))
        ).run();
    }

    private void run() {

        patientUUIDToPatientIdMapping = new HashMap<>();
        fileToPatientListMap = new HashMap<>();
        try {
            Files.lines(patientUUIDToIdMappingFile.toPath())
                    .skip(1)
                    .map(l -> l.split(","))
                    .filter(columns -> columns.length == 2)
                    .forEach(columns -> {
                        patientUUIDToPatientIdMapping.put(columns[0], columns[1]);
                    });

            Files.lines(vcfPatientMappingFile.toPath())
                    .skip(1)
                    .map(l -> l.split(","))
                    .filter(columns -> validPatientTypes.contains(columns[4]))
                    .forEach(columns -> {
                        String patientUuid = columns[0];
                        String vcfFile = columns[1].substring(columns[1].lastIndexOf("/") + 1);
                        List<String> patientList = Optional.ofNullable(fileToPatientListMap.get(vcfFile)).orElseGet(ArrayList::new);
                        patientList.add(patientUuid);

                        if (patientUUIDToPatientIdMapping.get(patientUuid) != null) {
                            fileToPatientListMap.put(vcfFile, patientList);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        writeVcfIndexes();
    }

    private void writeVcfIndexes() {
        Map<String, List<String>> groupToVcfMapping = new HashMap<>();

        for (String fileName : fileToPatientListMap.keySet()) {
            String baseFile = fileName.substring(0, fileName.indexOf(".chr"));
            List<String> vcfFiles = groupToVcfMapping.getOrDefault(baseFile, new ArrayList<>());
            vcfFiles.add(fileName);
            groupToVcfMapping.put(baseFile, vcfFiles);
        }

        groupToVcfMapping.keySet()
                .stream()
                .forEach(vcfGroup -> {
                    writeVcfIndex(vcfGroup, groupToVcfMapping.get(vcfGroup));
                });
    }

    private void writeVcfIndex(String vcfGroup, List<String> vcfFiles) {
        try {
            FileWriter fileWriter = new FileWriter(vcfIndexOutputDirectory.getAbsolutePath() + "/" + vcfGroup + "-vcfIndex.tsv");
            fileWriter.write("\"vcf_path\"\t\"chromosome\"\t\"isAnnotated\"\t\"isGzipped\"\t\"sample_ids\"\t\"patient_ids\"\t\"sample_relationship\"\t\"related_sample_ids\"\n");

            for (String vcfFile : vcfFiles) {
                Set<String> validPatientUUIDs = fileToPatientListMap.get(vcfFile)
                        .stream()
                        .filter(patientUUIDToPatientIdMapping::containsKey)
                        .collect(Collectors.toSet());
                List<String> patentIds = validPatientUUIDs.stream().map(patientUUIDToPatientIdMapping::get).filter(Objects::nonNull).toList();
                fileWriter.write("\"" + VCF_INDEX_DIRECTORY + "/" + vcfFile + "\"\t\"" + extractChromosome(vcfFile) + "\"\t\"1\"\t\"1\"\t");
                fileWriter.write("\"" + COMMA_JOINER.join(validPatientUUIDs) + "\"\t\"" + COMMA_JOINER.join(patentIds) + "\"\n");
            }

            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extractChromosome(String fileName) {
        return fileName.substring(fileName.indexOf(".chr") + 4, fileName.lastIndexOf(".vcf.gz"));
    }
}
