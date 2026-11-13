package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryUnavailableException;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    /**
     * The concept paths whose observations carry timestamps, as declared before the header is materialized
     */
    private Set<String> timestampedPaths = Set.of();
    /**
     * The timestamp field of each entry in {@link #formattedFields}, index-aligned with it, null for a field that has none
     */
    private List<String> timestampFields;
    /**
     * The field names of the patient record in schema order: every formatted field, each followed by its timestamp field if it has one
     */
    private List<String> patientRecordFields;
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

    /**
     * Records the fields of this export. Nothing is fetched or written until the first rows arrive or the writer is closed.
     *
     * @param data the concept paths to export, in column order
     */
    @Override
    public void writeHeader(String[] data) {
        originalFields = List.of(data);
        formattedFields = originalFields.stream().map(this::formatFieldName).collect(Collectors.toList());
    }

    /**
     * Records which concept paths get a timestamp field. The field is named after the concept's formatted name plus {@code _timestamp},
     * with a numeric suffix when that name is taken, and sits right after the concept in the patient record.
     *
     * @param conceptPaths the concept paths, as passed to {@link #writeHeader(String[])}, that have at least one dated observation
     * @throws IllegalStateException if the Avro header has already been written
     */
    @Override
    public void declareTimestampedFields(Set<String> conceptPaths) {
        if (dataFileWriter != null) {
            throw new IllegalStateException("Timestamped fields must be declared before the first rows are written");
        }
        timestampedPaths = Set.copyOf(conceptPaths);
    }

    /**
     * Names the timestamp field of every declared concept and lays out the patient record fields. A name that collides with another field
     * gets the first free numeric suffix, starting at {@code _2}.
     */
    private void resolveTimestampFields() {
        Set<String> takenNames = new HashSet<>(formattedFields);
        timestampFields = new ArrayList<>();
        patientRecordFields = new ArrayList<>();
        for (int i = 0; i < formattedFields.size(); i++) {
            String field = formattedFields.get(i);
            String timestampField = null;
            if (timestampedPaths.contains(originalFields.get(i)) && !isSingularField(field)) {
                timestampField = field + "_timestamp";
                for (int suffix = 2; !takenNames.add(timestampField); suffix++) {
                    timestampField = field + "_timestamp_" + suffix;
                }
            }
            timestampFields.add(timestampField);
            patientRecordFields.add(field);
            if (timestampField != null) {
                patientRecordFields.add(timestampField);
            }
        }
    }

    private void ensureHeaderWritten() {
        if (dataFileWriter == null) {
            materializeHeader();
        }
    }

    /**
     * Fetches the concept details, then creates the Avro file and writes the metadata and data dictionary records. The dictionary is asked
     * first, so a failed call leaves no Avro content behind.
     *
     * @throws DictionaryUnavailableException if the concept details cannot be fetched
     */
    private void materializeHeader() {
        Map<String, Concept> conceptMap = fetchConcepts();
        resolveTimestampFields();

        dataDictionarySchema = SchemaBuilder.record(dataDictionaryTableName).fields().requiredString("concept_path").name("drs_uri")
            .type(SchemaBuilder.array().items(SchemaBuilder.nullable().stringType())).noDefault().nullableString("display", "null")
            .nullableString("dataset", "null").nullableString("description", "null").endRecord();

        SchemaBuilder.FieldAssembler<Schema> patientRecords = SchemaBuilder.record(patientTableName).fields();
        patientRecordFields.forEach(field -> {
            if (isSingularField(field)) {
                patientRecords.nullableString(field, "null");
            } else {
                patientRecords.name(field).type(SchemaBuilder.array().items(SchemaBuilder.nullable().stringType())).noDefault();
            }

        });
        patientDataSchema = patientRecords.endRecord();

        Schema objectSchema = Schema.createUnion(metadataSchema, patientDataSchema, dataDictionarySchema);

        entityFieldAssembler = SchemaBuilder.record("entity").namespace("edu.harvard.dbmi").fields();
        entityFieldAssembler = entityFieldAssembler.name("object").type(objectSchema).noDefault();
        entityFieldAssembler.nullableString("id", "null");
        entityFieldAssembler.requiredString("name");
        entityFieldAssembler = entityFieldAssembler.name("relations").type(SchemaBuilder.array().items(relationSchema)).noDefault();
        entitySchema = entityFieldAssembler.endRecord();

        DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<GenericRecord>(entitySchema);
        DataFileWriter<GenericRecord> openedWriter = new DataFileWriter<GenericRecord>(datumWriter);
        try {
            log.info("Creating temp avro file at " + file.getAbsoluteFile());
            openedWriter.setCodec(CodecFactory.deflateCodec(CodecFactory.DEFAULT_DEFLATE_LEVEL));
            openedWriter.create(entitySchema, file);
            writeMetadata(openedWriter);
            writeDataDictionary(conceptMap, openedWriter);
        } catch (IOException e) {
            closeAfterFailure(openedWriter, e);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeAfterFailure(openedWriter, e);
            throw e;
        }
        dataFileWriter = openedWriter;
    }

    private void closeAfterFailure(DataFileWriter<GenericRecord> openedWriter, Exception failure) {
        try {
            openedWriter.close();
        } catch (IOException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private Map<String, Concept> fetchConcepts() {
        List<String> conceptPaths = new ArrayList<>();
        for (int i = 0; i < formattedFields.size(); i++) {
            if (!"patient_id".equals(formattedFields.get(i))) {
                conceptPaths.add(originalFields.get(i));
            }
        }
        return dictionaryService.getConcepts(conceptPaths).stream()
            .collect(Collectors.toMap(Concept::conceptPath, Function.identity(), (first, duplicate) -> first));
    }

    private void writeDataDictionary(Map<String, Concept> conceptMap, DataFileWriter<GenericRecord> openedWriter) {
        GenericRecord entityRecord = new GenericData.Record(entitySchema);;

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
                            String[] drsUriArray = OBJECT_MAPPER.readValue(drsUriJson, String[].class);
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

            log.debug("Writing {} to data dictionary table with drs_uris: {}", formattedField, drsUris);
            entityRecord.put("object", dataDictionaryData);
            entityRecord.put("name", dataDictionaryTableName);
            entityRecord.put("id", formattedField);
            entityRecord.put("relations", List.of());

            try {
                openedWriter.append(entityRecord);
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

    /**
     * Builds the metadata node of one table, with one property per field and no links, values or ontology references.
     *
     * @param tableName the name of the table the node describes
     * @param fields the field names of that table, in schema order
     * @return the node record
     */
    private GenericRecord nodeFor(String tableName, List<String> fields) {
        List<GenericRecord> propertiesList = new ArrayList<>();
        for (String field : fields) {
            GenericRecord properties = new GenericData.Record(propertiesSchema);
            properties.put("name", field);
            properties.put("ontology_reference", "");
            properties.put("values", Map.of());
            propertiesList.add(properties);
        }
        GenericRecord nodeData = new GenericData.Record(nodeSchema);
        nodeData.put("name", tableName);
        nodeData.put("ontology_reference", "");
        nodeData.put("values", Map.of());
        nodeData.put("links", List.of());
        nodeData.put("properties", propertiesList);
        return nodeData;
    }

    private void writeMetadata(DataFileWriter<GenericRecord> openedWriter) {
        GenericRecord entityRecord = new GenericData.Record(entitySchema);

        List<GenericRecord> nodeList = new ArrayList<>();
        nodeList.add(nodeFor(this.patientTableName, patientRecordFields));
        nodeList.add(nodeFor(this.dataDictionaryTableName, DATA_DICTIONARY_FIELDS));

        GenericRecord metadata = new GenericData.Record(metadataSchema);
        metadata.put("misc", "");
        metadata.put("nodes", nodeList);

        entityRecord.put("object", metadata);
        entityRecord.put("name", "metadata");
        entityRecord.put("id", "null");
        entityRecord.put("relations", List.of());

        try {
            openedWriter.append(entityRecord);
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
        writePatients(entities, null);
    }

    /**
     * Writes patient rows with the timestamps of their values.
     *
     * @param entities one entry per patient, each holding one list of values per field
     * @param timestamps one entry per patient, in the same order, each holding one entry per field: for a declared field a list as long as
     *        its values, for any other field null
     * @throws IllegalArgumentException if the two arguments differ in length, a timestamp row does not match the number of fields, or a
     *         declared field's timestamps and values differ in length
     */
    @Override
    public void writeMultiValueEntity(Collection<List<List<String>>> entities, Collection<List<List<String>>> timestamps) {
        if (timestamps.size() != entities.size()) {
            throw new IllegalArgumentException("Expected one timestamp row per entity");
        }
        writePatients(entities, timestamps.iterator());
    }

    private void writePatients(Collection<List<List<String>>> entities, Iterator<List<List<String>>> timestampRows) {
        ensureHeaderWritten();
        entities.forEach(entity -> {
            if (entity.size() != formattedFields.size()) {
                throw new IllegalArgumentException("Entity length much match the number of fields in this document");
            }
            GenericRecord patientData = new GenericData.Record(patientDataSchema);
            putTimestamps(patientData, entity, timestampRows == null ? null : timestampRows.next());
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

    /**
     * Puts the timestamp array of every declared field into a patient record.
     *
     * @param patientData the patient record being built
     * @param entity the patient's values, one list per field
     * @param timestampRow the patient's timestamps, one entry per field, or null when the caller passed none
     * @throws IllegalArgumentException if the row does not match the number of fields, or a declared field's timestamps and values differ
     *         in length
     */
    private void putTimestamps(GenericRecord patientData, List<List<String>> entity, List<List<String>> timestampRow) {
        if (timestampRow != null && timestampRow.size() != formattedFields.size()) {
            throw new IllegalArgumentException("Timestamp row length must match the number of fields in this document");
        }
        for (int i = 0; i < formattedFields.size(); i++) {
            String timestampField = timestampFields.get(i);
            if (timestampField == null) {
                continue;
            }
            int valueCount = entity.get(i) != null ? entity.get(i).size() : 0;
            List<String> fieldTimestamps = timestampRow != null && timestampRow.get(i) != null ? timestampRow.get(i) : List.of();
            if (fieldTimestamps.size() != valueCount) {
                throw new IllegalArgumentException(
                    "Field " + formattedFields.get(i) + " has " + valueCount + " values but " + fieldTimestamps.size() + " timestamps"
                );
            }
            patientData.put(timestampField, fieldTimestamps);
        }
    }

    @Override
    public void close() {
        ensureHeaderWritten();
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
