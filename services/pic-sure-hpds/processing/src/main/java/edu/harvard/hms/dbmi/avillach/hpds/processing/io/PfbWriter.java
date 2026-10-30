package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
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
 * metadata about variables: each patient column's concept path, display and description</li> <li>relations: Contains relational data about
 * entities. Currently empty</li> </ul>
 */
public class PfbWriter implements ResultWriter {

    public static final String PATIENT_TABLE_PREFIX = "pic-sure-patients-";
    public static final String DATA_DICTIONARY_TABLE_PREFIX = "pic-sure-data-dictionary-";
    public static final List<String> DATA_DICTIONARY_FIELDS = List.of("concept_path", "display", "dataset", "description", "drs_uri");
    private static final String PATIENT_ID = "patient_id";
    private static final Logger log = LoggerFactory.getLogger(PfbWriter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Schema STRING = Schema.create(Schema.Type.STRING);
    private static final Schema NULLABLE_STRING = Schema.createUnion(Schema.create(Schema.Type.NULL), STRING);
    private static final Schema NULLABLE_STRING_ARRAY = Schema.createArray(NULLABLE_STRING);

    /**
     * A hardcoded set of fields that should be a single value instead of an array.
     *
     * todo: introduce an attribute on concept paths specifying if they can contain multiple values
     */
    private static final Set<String> SINGULAR_FIELDS = Set.of(PATIENT_ID);

    /**
     * Location of the file being written to
     */
    private final File file;
    private final DictionaryService dictionaryService;
    private final String patientTableName;
    private final String dataDictionaryTableName;

    /**
     * The original (before formatting for avro) concept path values
     */
    private List<String> originalFields;
    /**
     * The avro formatted concept path values. Avro only allows alphanumeric values and underscores as field names
     */
    private List<String> formattedFields;
    private PfbSchema schema;
    private DataFileWriter<GenericRecord> dataFileWriter;

    public PfbWriter(File tempFile, String queryId, DictionaryService dictionaryService) {
        this.file = tempFile;
        this.dictionaryService = dictionaryService;
        this.patientTableName = formatFieldName(PATIENT_TABLE_PREFIX + queryId);
        this.dataDictionaryTableName = formatFieldName(DATA_DICTIONARY_TABLE_PREFIX + queryId);
    }

    @Override
    public void writeHeader(String[] data) {
        originalFields = List.of(data);
        formattedFields = uniqueFieldNames(originalFields);
        Map<String, Concept> concepts = lookupConcepts();
        schema = new PfbSchema(List.of(patientNode(concepts), dataDictionaryNode()));

        dataFileWriter = new DataFileWriter<>(new GenericDatumWriter<>(schema.entitySchema()));
        try {
            log.info("Creating temp avro file at " + file.getAbsoluteFile());
            dataFileWriter.setCodec(CodecFactory.deflateCodec(CodecFactory.DEFAULT_DEFLATE_LEVEL));
            dataFileWriter.create(schema.entitySchema(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        append(schema.metadataEntity());
        writeDataDictionary(concepts);
    }

    /**
     * Schema containing one row per patient and one column per concept path exported
     */
    private PfbSchema.Node patientNode(Map<String, Concept> concepts) {
        List<PfbSchema.Property> properties = new ArrayList<>();
        for (int i = 0; i < formattedFields.size(); i++) {
            String originalField = originalFields.get(i);
            if (isSingularField(originalField)) {
                properties.add(new PfbSchema.Property(formattedFields.get(i), NULLABLE_STRING, Map.of()));
            } else {
                properties.add(
                    new PfbSchema.Property(
                        formattedFields.get(i), NULLABLE_STRING_ARRAY, conceptMetadata(originalField, concepts.get(originalField))
                    )
                );
            }
        }
        return new PfbSchema.Node(patientTableName, properties, List.of());
    }

    /** Metadata values for a patient column. PFB metadata maps hold no nulls, so missing dictionary fields are left out. */
    private static Map<String, String> conceptMetadata(String conceptPath, Concept concept) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("concept_path", conceptPath);
        if (concept != null) {
            putIfPresent(values, "display", concept.display());
            putIfPresent(values, "dataset", concept.dataset());
            putIfPresent(values, "description", concept.description());
        }
        return values;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    /**
     * Data dictionary schema containing one row per concept path exported and various metadata columns
     */
    private PfbSchema.Node dataDictionaryNode() {
        Map<String, Schema> types = Map.of(
            "concept_path", STRING, "display", NULLABLE_STRING, "dataset", NULLABLE_STRING, "description", NULLABLE_STRING, "drs_uri",
            NULLABLE_STRING_ARRAY
        );
        List<PfbSchema.Property> properties =
            DATA_DICTIONARY_FIELDS.stream().map(field -> new PfbSchema.Property(field, types.get(field), Map.of())).toList();
        return new PfbSchema.Node(dataDictionaryTableName, properties, List.of());
    }

    private Map<String, Concept> lookupConcepts() {
        if (dictionaryService == null) {
            return Map.of();
        }
        List<String> conceptPaths = originalFields.stream().filter(field -> !PATIENT_ID.equals(field)).toList();
        try {
            return dictionaryService.getConcepts(conceptPaths).stream()
                .collect(Collectors.toMap(Concept::conceptPath, Function.identity(), (first, second) -> first));
        } catch (RuntimeException e) {
            log.error("Error fetching concepts from dictionary service", e);
            return Map.of();
        }
    }

    private void writeDataDictionary(Map<String, Concept> concepts) {
        for (int i = 0; i < formattedFields.size(); i++) {
            String originalField = originalFields.get(i);
            if (PATIENT_ID.equals(originalField)) {
                continue;
            }
            Concept concept = concepts.get(originalField);
            Map<String, Object> row = new HashMap<>();
            row.put("concept_path", originalField);
            row.put("drs_uri", drsUris(concept));
            if (concept != null) {
                row.put("display", concept.display());
                row.put("dataset", concept.dataset());
                row.put("description", concept.description());
            }
            append(schema.entity(dataDictionaryTableName, formattedFields.get(i), row, List.of()));
        }
    }

    private static List<String> drsUris(Concept concept) {
        String drsUriJson = concept == null || concept.meta() == null ? null : concept.meta().get("drs_uri");
        if (drsUriJson == null) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(drsUriJson, String[].class));
        } catch (JsonProcessingException e) {
            log.error("Error parsing drs_uri as json: " + drsUriJson);
            return List.of();
        }
    }

    /**
     * Transforms our variable names to once that are valid avro fields. We replace invalid characters with underscores and add a leading
     * underscore if the variable starts with a number
     */
    protected String formatFieldName(String s) {
        String formattedFieldName = s.replaceAll("\\W", "_");
        if (formattedFieldName.isEmpty() || Character.isDigit(formattedFieldName.charAt(0))) {
            return "_" + formattedFieldName;
        }
        return formattedFieldName;
    }

    /** Formats each name, then suffixes {@code _2}, {@code _3}, ... onto any that would repeat an earlier one. */
    List<String> uniqueFieldNames(List<String> fields) {
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>(fields.size());
        for (String field : fields) {
            String base = formatFieldName(field);
            String name = base;
            for (int suffix = 2; !used.add(name); suffix++) {
                name = base + "_" + suffix;
            }
            names.add(name);
        }
        return names;
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
            Map<String, Object> row = new HashMap<>();
            String patientId = "";
            for (int i = 0; i < formattedFields.size(); i++) {
                List<String> values = entity.get(i) != null ? entity.get(i) : List.of();
                if (PATIENT_ID.equals(originalFields.get(i))) {
                    patientId = values.isEmpty() ? "" : values.getFirst();
                }
                if (isSingularField(originalFields.get(i))) {
                    row.put(formattedFields.get(i), values.isEmpty() ? "" : values.getFirst());
                } else {
                    row.put(formattedFields.get(i), values);
                }
            }
            append(schema.entity(patientTableName, patientId, row, List.of()));
        });
    }

    private boolean isSingularField(String field) {
        return SINGULAR_FIELDS.contains(field);
    }

    private void append(GenericRecord entity) {
        try {
            dataFileWriter.append(entity);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
