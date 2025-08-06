package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes HPDS data in PFB format. PFB is an Avro schema specifically created for biomedical data. See <a
 * href="https://uc-cdis.github.io/pypfb/">https://uc-cdis.github.io/pypfb/</a> for more details.
 *
 * Our PFB format has 4 entities currently: <ul> <li>pic-sure-patients: Contains patient data, with one row per patient</li>
 * <li>pic-sure-data-dictionary: Contains variable metadata with one row per variable exported</li> <li>metadata: Contains ontological
 * metadata about variables. Currently empty</li> <li>relations: Contains relational data about entities. Currently empty</li> </ul>
 */
public class PfbWriter implements ResultWriter {

    public static final String PATIENT_TABLE_PREFIX = "pic-sure-patients-";
    public static final String DATA_DICTIONARY_TABLE_PREFIX = "pic-sure-data-dictionary-";
    public static final List<String> DATA_DICTIONARY_FIELDS = List.of("concept_path", "display", "dataset", "description", "drs_uri");
    private static final Logger log = LoggerFactory.getLogger(PfbWriter.class);

    private final DictionaryService dictionaryService;

    private final Schema metadataSchema;
    private final Schema nodeSchema;

    private final Schema propertiesSchema;

    private final String patientTableName;
    private final String dataDictionaryTableName;
    private SchemaBuilder.FieldAssembler<Schema> entityFieldAssembler;

    /**
     * The original (before formatting for avro) concept path values
     */
    private List<String> originalFields;
    /**
     * The avro formatted concept path values. Avro only allows alphanumeric values and underscores as field names
     */
    private List<String> formattedFields;
    private DataFileWriter<GenericRecord> dataFileWriter;
    /**
     * Location of the file being written to
     */
    private File file;
    /**
     * The entity schema is a union of our custom entities, plus the PFB defined relation and metadata entities
     */
    private Schema entitySchema;
    /**
     * Schema containing one row per patient and one column per concept path exported
     */
    private Schema patientDataSchema;
    /**
     * Data dictionary schema containing one row per concept path exported and various metadata columns
     */
    private Schema dataDictionarySchema;
    /**
     * Relational data about entities. Currently empty
     */
    private Schema relationSchema;

    /**
     * A hardcoded set of fields that should be a single value instead of an array.
     *
     * todo: introduce an attribute on concept paths specifying if they can contain multiple values
     */
    private static final Set<String> SINGULAR_FIELDS = Set.of("patient_id");

    public PfbWriter(File tempFile, String queryId, DictionaryService dictionaryService) {
        this.file = tempFile;
        this.dictionaryService = dictionaryService;
        this.patientTableName = formatFieldName(PATIENT_TABLE_PREFIX + queryId);
        this.dataDictionaryTableName = formatFieldName(DATA_DICTIONARY_TABLE_PREFIX + queryId);
        entityFieldAssembler = SchemaBuilder.record("entity").namespace("edu.harvard.dbmi").fields();


        Schema linksSchema = SchemaBuilder.record("Link").fields().requiredString("dst").name("multiplicity")
            .type(SchemaBuilder.enumeration("Multiplicity").symbols("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY")).noDefault()
            .endRecord();

        propertiesSchema = SchemaBuilder.record("Property").fields().requiredString("name").requiredString("ontology_reference")
            .name("values").type(SchemaBuilder.map().values(SchemaBuilder.nullable().stringType())).noDefault().endRecord();

        SchemaBuilder.FieldAssembler<Schema> nodeRecord = SchemaBuilder.record("nodes").fields().requiredString("name")
            .nullableString("ontology_reference", "null").name("links").type(SchemaBuilder.array().items(linksSchema)).noDefault()
            .name("properties").type(SchemaBuilder.array().items(propertiesSchema)).noDefault().name("values")
            .type(SchemaBuilder.map().values(SchemaBuilder.nullable().stringType())).noDefault();
        nodeSchema = nodeRecord.endRecord();

        SchemaBuilder.FieldAssembler<Schema> metadataRecord = SchemaBuilder.record("metadata").fields();
        metadataRecord.requiredString("misc");
        metadataRecord = metadataRecord.name("nodes").type(SchemaBuilder.array().items(nodeSchema)).noDefault();
        metadataSchema = metadataRecord.endRecord();


        SchemaBuilder.FieldAssembler<Schema> relationRecord =
            SchemaBuilder.record("Relation").fields().requiredString("dst_name").requiredString("dst_id");
        relationSchema = relationRecord.endRecord();
    }

    @Override
    public void writeHeader(String[] data) {
        originalFields = List.of(data);
        formattedFields = originalFields.stream().map(this::formatFieldName).collect(Collectors.toList());

        dataDictionarySchema = SchemaBuilder.record(dataDictionaryTableName).fields().requiredString("concept_path").name("drs_uri")
            .type(SchemaBuilder.array().items(SchemaBuilder.nullable().stringType())).noDefault().nullableString("display", "null")
            .nullableString("dataset", "null").nullableString("description", "null").endRecord();

        SchemaBuilder.FieldAssembler<Schema> patientRecords = SchemaBuilder.record(patientTableName).fields();
        formattedFields.forEach(field -> {
            if (isSingularField(field)) {
                patientRecords.nullableString(field, "null");
            } else {
                patientRecords.name(field).type(SchemaBuilder.array().items(SchemaBuilder.nullable().stringType())).noDefault();
            }

        });
        patientDataSchema = patientRecords.endRecord();

        Schema objectSchema = Schema.createUnion(metadataSchema, patientDataSchema, dataDictionarySchema);

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
        writeDataDictionary();
    }

    private void writeDataDictionary() {
        GenericRecord entityRecord = new GenericData.Record(entitySchema);;
        Map<String, Concept> conceptMap = Map.of();
        try {
            conceptMap =
                dictionaryService.getConcepts(originalFields).stream().collect(Collectors.toMap(Concept::conceptPath, Function.identity()));
        } catch (RuntimeException e) {
            log.error("Error fetching concepts from dictionary service", e);
        }

        for (int i = 0; i < formattedFields.size(); i++) {
            String formattedField = formattedFields.get(i);
            if ("patient_id".equals(formattedField)) {
                continue;
            }
            GenericRecord dataDictionaryData = new GenericData.Record(dataDictionarySchema);
            dataDictionaryData.put("concept_path", formattedField);

            Concept concept = conceptMap.get(originalFields.get(i));
            List<String> drsUris = List.of();
            if (concept != null) {
                Map<String, String> meta = concept.meta();
                if (meta != null) {
                    String drsUriJson = meta.get("drs_uri");
                    if (drsUriJson != null) {
                        try {
                            String[] drsUriArray = new ObjectMapper().readValue(drsUriJson, String[].class);
                            drsUris = List.of(drsUriArray);
                        } catch (JsonProcessingException e) {
                            log.error("Error parsing drs_uri as json: " + drsUriJson);
                        }
                    }
                }
                dataDictionaryData.put("display", concept.display());
                dataDictionaryData.put("dataset", concept.dataset());
                dataDictionaryData.put("description", concept.description());
            }
            dataDictionaryData.put("drs_uri", drsUris);

            log.info("Writing " + formattedField + " to data dictonary table with drs_uris: " + drsUris);
            entityRecord.put("object", dataDictionaryData);
            entityRecord.put("name", dataDictionaryTableName);
            entityRecord.put("id", formattedField);
            entityRecord.put("relations", List.of());

            try {
                dataFileWriter.append(entityRecord);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private boolean isSingularField(String field) {
        return SINGULAR_FIELDS.contains(field);
    }

    /**
     * Transforms our variable names to once that are valid avro fields. We replace invalid characters with underscores and add a leading
     * underscore if the variable starts with a number
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
        List<GenericRecord> propertiesList = new ArrayList<>();
        GenericRecord nodeData = new GenericData.Record(nodeSchema);
        nodeData.put("name", this.patientTableName);
        nodeData.put("ontology_reference", "");
        nodeData.put("values", Map.of());
        nodeData.put("links", List.of());
        for (String field : formattedFields) {
            GenericRecord properties = new GenericData.Record(propertiesSchema);
            properties.put("name", field);
            properties.put("ontology_reference", "");
            properties.put("values", Map.of());
            propertiesList.add(properties);
        }
        nodeData.put("properties", propertiesList);
        nodeList.add(nodeData);


        propertiesList = new ArrayList<>();
        nodeData = new GenericData.Record(nodeSchema);
        nodeData.put("name", this.dataDictionaryTableName);
        nodeData.put("ontology_reference", "");
        nodeData.put("values", Map.of());
        nodeData.put("links", List.of());
        for (String field : DATA_DICTIONARY_FIELDS) {
            GenericRecord properties = new GenericData.Record(propertiesSchema);
            properties.put("name", field);
            properties.put("ontology_reference", "");
            properties.put("values", Map.of());
            propertiesList.add(properties);
        }
        nodeData.put("properties", propertiesList);
        nodeList.add(nodeData);

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
            if (entity.size() != formattedFields.size()) {
                throw new IllegalArgumentException("Entity length much match the number of fields in this document");
            }
            GenericRecord patientData = new GenericData.Record(patientDataSchema);
            String patientId = "";
            for (int i = 0; i < formattedFields.size(); i++) {
                if ("patient_id".equals(formattedFields.get(i))) {
                    patientId = (entity.get(i) != null && !entity.get(i).isEmpty()) ? entity.get(i).get(0) : "";
                }
                if (isSingularField(formattedFields.get(i))) {
                    String entityValue = (entity.get(i) != null && !entity.get(i).isEmpty()) ? entity.get(i).get(0) : "";
                    patientData.put(formattedFields.get(i), entityValue);
                } else {
                    List<String> fieldValue = entity.get(i) != null ? entity.get(i) : List.of();
                    patientData.put(formattedFields.get(i), fieldValue);
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
