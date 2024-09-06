package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

public class PfbWriter implements ResultWriter {

    public static final String PATIENT_TABLE_PREFIX = "pic-sure-";
    private Logger log = LoggerFactory.getLogger(PfbWriter.class);

    private final Schema metadataSchema;
    private final Schema nodeSchema;

    private final String queryId;

    private final String patientTableName;
    private SchemaBuilder.FieldAssembler<Schema> entityFieldAssembler;

    private List<String> fields;
    private DataFileWriter<GenericRecord> dataFileWriter;
    private File file;
    private Schema entitySchema;
    private Schema patientDataSchema;
    private Schema relationSchema;

    private static final Set<String> SINGULAR_FIELDS = Set.of("patient_id");

    public PfbWriter(File tempFile, String queryId) {
        this.file = tempFile;
        this.queryId = queryId;
        this.patientTableName = formatFieldName(PATIENT_TABLE_PREFIX + queryId);
        entityFieldAssembler = SchemaBuilder.record("entity")
                .namespace("edu.harvard.dbmi")
                .fields();

        SchemaBuilder.FieldAssembler<Schema> nodeRecord = SchemaBuilder.record("nodes")
                .fields()
                .requiredString("name")
                .nullableString("ontology_reference", "null")
                .name("values").type(SchemaBuilder.map().values(SchemaBuilder.nullable().stringType())).noDefault();
        nodeSchema = nodeRecord.endRecord();

        SchemaBuilder.FieldAssembler<Schema> metadataRecord = SchemaBuilder.record("metadata")
                .fields();
        metadataRecord.requiredString("misc");
        metadataRecord = metadataRecord.name("nodes").type(SchemaBuilder.array().items(nodeSchema)).noDefault();
        metadataSchema = metadataRecord.endRecord();


        SchemaBuilder.FieldAssembler<Schema> relationRecord = SchemaBuilder.record("Relation")
                .fields()
                .requiredString("dst_name")
                .requiredString("dst_id");
        relationSchema = relationRecord.endRecord();
    }

    @Override
    public void writeHeader(String[] data) {
        fields = Arrays.stream(data.clone()).map(this::formatFieldName).collect(Collectors.toList());
        SchemaBuilder.FieldAssembler<Schema> patientRecords = SchemaBuilder.record(patientTableName)
                .fields();

        fields.forEach(field -> {
            if (isSingularField(field)) {
                patientRecords.nullableString(field, "null");
            } else {
                patientRecords.name(field).type(SchemaBuilder.array().items(SchemaBuilder.nullable().stringType())).noDefault();
            }

        });
        patientDataSchema = patientRecords.endRecord();

        Schema objectSchema = Schema.createUnion(metadataSchema, patientDataSchema);

        entityFieldAssembler = entityFieldAssembler.name("object").type(objectSchema).noDefault();
        entityFieldAssembler.nullableString("id", "null");
        entityFieldAssembler.requiredString("name");
        entityFieldAssembler = entityFieldAssembler.name("relations").type(SchemaBuilder.array().items(relationSchema)).noDefault();
        entitySchema = entityFieldAssembler.endRecord();

        DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<GenericRecord>(entitySchema);
        dataFileWriter = new DataFileWriter<GenericRecord>(datumWriter);
        try {
            log.info("Creating temp avro file at " + file.getAbsoluteFile());
            dataFileWriter.setCodec(CodecFactory.deflateCodec(CodecFactory.DEFAULT_DEFLATE_LEVEL));
            dataFileWriter.create(entitySchema, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        writeMetadata();
    }

    private boolean isSingularField(String field) {
        return SINGULAR_FIELDS.contains(field);
    }

    /**
     * Transforms our variable names to once that are valid avro fields. We replace invalid characters with underscores
     * and add a leading underscore if the variable starts with a number
     */
    protected String formatFieldName(String s) {
        String formattedFieldName = s.replaceAll("\\W", "_");
        if (Character.isDigit(formattedFieldName.charAt(0))) {
            return "_" + formattedFieldName;
        }
        return formattedFieldName;
    }

    private void writeMetadata() {
        GenericRecord entityRecord = new GenericData.Record(entitySchema);

        List<GenericRecord> nodeList = new ArrayList<>();
        for (String field : fields) {
            GenericRecord nodeData = new GenericData.Record(nodeSchema);
            nodeData.put("name", field);
            nodeData.put("ontology_reference", "");
            nodeData.put("values", Map.of());
            nodeList.add(nodeData);
        }
        GenericRecord metadata = new GenericData.Record(metadataSchema);
        metadata.put("misc", "");
        metadata.put("nodes", nodeList);


        entityRecord.put("object", metadata);
        entityRecord.put("name", "metadata");
        entityRecord.put("id", "null");
        entityRecord.put("relations", List.of());

        try {
            dataFileWriter.append(entityRecord);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeEntity(Collection<String[]> entities) {
        throw new RuntimeException("Method not supported, use writeMultiValueEntity instead");
    }

    @Override
    public void writeMultiValueEntity(Collection<List<List<String>>> entities) {
        entities.forEach(entity -> {
            if (entity.size() != fields.size()) {
                throw new IllegalArgumentException("Entity length much match the number of fields in this document");
            }
            GenericRecord patientData = new GenericData.Record(patientDataSchema);
            String patientId = "";
            for(int i = 0; i < fields.size(); i++) {
                if ("patient_id".equals(fields.get(i))) {
                    patientId = (entity.get(i) != null && !entity.get(i).isEmpty()) ? entity.get(i).get(0) : "";
                }
                if (isSingularField(fields.get(i))) {
                    String entityValue = (entity.get(i) != null && !entity.get(i).isEmpty()) ? entity.get(i).get(0) : "";
                    patientData.put(fields.get(i), entityValue);
                } else {
                    List<String> fieldValue = entity.get(i) != null ? entity.get(i) : List.of();
                    patientData.put(fields.get(i), fieldValue);
                }
            }


            GenericRecord entityRecord = new GenericData.Record(entitySchema);
            entityRecord.put("object", patientData);
            entityRecord.put("name", patientTableName);
            entityRecord.put("id", patientId);
            entityRecord.put("relations", List.of());

            try {
                dataFileWriter.append(entityRecord);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public void close() {
        try {
            dataFileWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public MediaType getResponseType() {
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
