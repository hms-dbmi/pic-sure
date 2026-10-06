package edu.harvard.hms.dbmi.avillach.auth.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity(name = "user_consents")
public class UserConsents extends BaseEntity {

    @Column(unique = true, name = "user_id")
    private UUID userId;


    @Convert(converter = ConsentsJsonConverter.class)
    private Set<String> consents;

    public UUID getUserId() {
        return userId;
    }

    public UserConsents setUserId(UUID userId) {
        this.userId = userId;
        return this;
    }

    public Set<String> getConsents() {
        return consents;
    }

    public UserConsents setConsents(Set<String> consents) {
        this.consents = consents;
        return this;
    }

    protected static class ConsentsJsonConverter implements AttributeConverter<Set<String>, String> {
        private static final Logger logger = LoggerFactory.getLogger(ConsentsJsonConverter.class);
        private static final ObjectMapper objectMapper = new ObjectMapper();
        private static final TypeReference<Set<String>> SET_OF_STRING_TYPE_REF = new TypeReference<Set<String>>() {};
        // Legacy rows store a map of consent groups; this group holds all of the user's consents.
        private static final String LEGACY_CONSENTS_KEY = "\\_consents\\";

        @Override
        public String convertToDatabaseColumn(Set<String> strings) {
            try {
                return objectMapper.writeValueAsString(strings);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Never throws: an unreadable value is treated as no consents, so the login that rebuilds the row is not blocked by it.
         */
        @Override
        public Set<String> convertToEntityAttribute(String s) {
            if (s == null) {
                return new HashSet<>();
            }
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node != null && node.isObject()) {
                    node = node.path(LEGACY_CONSENTS_KEY);
                }
                if (node == null || !node.isArray()) {
                    logger.warn("Treating unrecognized user consents value as no consents");
                    return new HashSet<>();
                }
                return objectMapper.convertValue(node, SET_OF_STRING_TYPE_REF);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                logger.warn("Treating unreadable user consents value as no consents", e);
                return new HashSet<>();
            }
        }
    }
}
