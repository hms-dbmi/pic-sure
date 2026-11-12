package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A PFB file's schema: the fixed pypfb envelope from {@code pfb/pfb-envelope.avsc}, with one record per {@link Node} added to the
 * envelope's {@code object} union. A new kind of entity is a new {@link Node}; the envelope does not change.
 */
public class PfbSchema {

    static final String METADATA_ENTITY_NAME = "Metadata";
    private static final String ENVELOPE_RESOURCE = "/pfb/pfb-envelope.avsc";
    private static final Schema ENVELOPE = loadEnvelope();

    public enum Multiplicity {
        ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
    }

    /**
     * One column of a node.
     *
     * @param values descriptive metadata written to the PFB metadata entity, such as the original concept path
     */
    public record Property(String name, Schema type, Map<String, String> values) {
    }

    /** A link from a node to the node named {@code dst}. */
    public record Link(String dst, String name, Multiplicity multiplicity) {
    }

    /**
     * Relational data about entities. Currently empty
     */
    public record Relation(String dstName, String dstId) {
    }

    /** One entity type: a record in the {@code object} union and a node in the metadata entity. {@code name} must be a valid Avro name. */
    public record Node(String name, List<Property> properties, List<Link> links) {

        Schema recordSchema() {
            List<Schema.Field> fields = properties.stream().map(property -> {
                Object defaultValue = isNullable(property.type()) ? Schema.Field.NULL_DEFAULT_VALUE : null;
                return new Schema.Field(property.name(), property.type(), null, defaultValue);
            }).toList();
            return Schema.createRecord(name, null, null, false, fields);
        }

        private static boolean isNullable(Schema type) {
            return type.getType() == Schema.Type.UNION && type.getTypes().getFirst().getType() == Schema.Type.NULL;
        }
    }

    private final List<Node> nodes;
    private final Map<String, Schema> recordSchemas = new LinkedHashMap<>();
    /**
     * The entity schema is a union of our custom entities, plus the PFB defined relation and metadata entities
     */
    private final Schema entitySchema;
    private final Schema metadataSchema;

    public PfbSchema(List<Node> nodes) {
        this.nodes = List.copyOf(nodes);
        nodes.forEach(node -> recordSchemas.put(node.name(), node.recordSchema()));

        Schema.Field envelopeObject = ENVELOPE.getField("object");
        metadataSchema = envelopeObject.schema().getTypes().getFirst();
        List<Schema> objectTypes = new ArrayList<>();
        objectTypes.add(metadataSchema);
        objectTypes.addAll(recordSchemas.values());

        List<Schema.Field> fields = ENVELOPE.getFields().stream()
            .map(
                field -> field.name().equals(envelopeObject.name()) ? new Schema.Field(field, Schema.createUnion(objectTypes))
                    : new Schema.Field(field, field.schema())
            ).toList();
        entitySchema = Schema.createRecord(ENVELOPE.getName(), ENVELOPE.getDoc(), ENVELOPE.getNamespace(), false, fields);
    }

    public Schema entitySchema() {
        return entitySchema;
    }

    /** The metadata entity describing every node. pypfb writes it first, with a null id. */
    public GenericRecord metadataEntity() {
        Schema nodeSchema = metadataSchema.getField("nodes").schema().getElementType();
        Schema linkSchema = nodeSchema.getField("links").schema().getElementType();
        Schema propertySchema = nodeSchema.getField("properties").schema().getElementType();
        Schema multiplicitySchema = linkSchema.getField("multiplicity").schema();

        List<GenericRecord> nodeRecords = nodes.stream().map(node -> {
            GenericRecord nodeRecord = new GenericData.Record(nodeSchema);
            nodeRecord.put("name", node.name());
            nodeRecord.put("ontology_reference", "");
            nodeRecord.put("values", Map.of());
            nodeRecord.put("links", node.links().stream().map(link -> {
                GenericRecord linkRecord = new GenericData.Record(linkSchema);
                linkRecord.put("multiplicity", new GenericData.EnumSymbol(multiplicitySchema, link.multiplicity().name()));
                linkRecord.put("dst", link.dst());
                linkRecord.put("name", link.name());
                return linkRecord;
            }).toList());
            nodeRecord.put("properties", node.properties().stream().map(property -> {
                GenericRecord propertyRecord = new GenericData.Record(propertySchema);
                propertyRecord.put("name", property.name());
                propertyRecord.put("ontology_reference", "");
                propertyRecord.put("values", property.values());
                return propertyRecord;
            }).toList());
            return nodeRecord;
        }).toList();

        GenericRecord metadata = new GenericData.Record(metadataSchema);
        metadata.put("nodes", nodeRecords);
        metadata.put("misc", Map.of());
        return envelope(null, METADATA_ENTITY_NAME, metadata, List.of());
    }

    /** An entity of {@code nodeName} whose columns are set from {@code values}; a column missing from it is null. */
    public GenericRecord entity(String nodeName, String id, Map<String, ?> values, List<Relation> relations) {
        Schema recordSchema = recordSchemas.get(nodeName);
        if (recordSchema == null) {
            throw new IllegalArgumentException("No PFB node named " + nodeName);
        }
        GenericRecord object = new GenericData.Record(recordSchema);
        values.forEach(object::put);
        return envelope(id, nodeName, object, relations);
    }

    private GenericRecord envelope(String id, String name, GenericRecord object, List<Relation> relations) {
        Schema relationSchema = entitySchema.getField("relations").schema().getElementType();
        GenericRecord entity = new GenericData.Record(entitySchema);
        entity.put("id", id);
        entity.put("name", name);
        entity.put("object", object);
        entity.put("relations", relations.stream().map(relation -> {
            GenericRecord relationRecord = new GenericData.Record(relationSchema);
            relationRecord.put("dst_id", relation.dstId());
            relationRecord.put("dst_name", relation.dstName());
            return relationRecord;
        }).toList());
        return entity;
    }

    private static Schema loadEnvelope() {
        try (InputStream in = PfbSchema.class.getResourceAsStream(ENVELOPE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing PFB envelope schema " + ENVELOPE_RESOURCE);
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
