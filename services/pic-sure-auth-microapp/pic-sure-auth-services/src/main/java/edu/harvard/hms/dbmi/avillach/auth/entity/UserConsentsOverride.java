package edu.harvard.hms.dbmi.avillach.auth.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity(name = "user_consents_override")
public class UserConsentsOverride extends BaseEntity {

    @Column(unique = true, name = "user_id")
    private UUID userId;


    @Convert(converter = ConsentsJsonConverter.class)
    private Set<String> consents;

    @Column(name = "enabled")
    private boolean enabled;

    public UUID getUserId() {
        return userId;
    }

    public UserConsentsOverride setUserId(UUID userId) {
        this.userId = userId;
        return this;
    }

    public Set<String> getConsents() {
        return consents;
    }

    public UserConsentsOverride setConsents(Set<String> consents) {
        this.consents = consents;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UserConsentsOverride setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    protected static class ConsentsJsonConverter implements AttributeConverter<Map<String, Set<String>>, String> {
        private static final ObjectMapper objectMapper = new ObjectMapper();
        private static final TypeReference<Map<String, Set<String>>> SET_OF_STRING_TYPE_REF = new TypeReference<Map<String, Set<String>>>() {};

        @Override
        public String convertToDatabaseColumn(Map<String, Set<String>> strings) {
            try {
                return objectMapper.writeValueAsString(strings);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Map<String, Set<String>> convertToEntityAttribute(String s) {
            try {
                return objectMapper.readValue(s, SET_OF_STRING_TYPE_REF);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
