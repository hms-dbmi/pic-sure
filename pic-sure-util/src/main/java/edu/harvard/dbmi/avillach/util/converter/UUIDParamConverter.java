package edu.harvard.dbmi.avillach.util.converter;

import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

import javax.ws.rs.ext.ParamConverter;
import java.util.UUID;

public class UUIDParamConverter implements ParamConverter<UUID> {

        @Override
        public UUID fromString(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
            }
        }

        @Override
        public String toString(UUID value) {
            return value.toString();
        }
}
