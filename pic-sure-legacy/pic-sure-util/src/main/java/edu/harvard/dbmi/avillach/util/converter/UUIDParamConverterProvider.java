package edu.harvard.dbmi.avillach.util.converter;

import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import javax.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
public class UUIDParamConverterProvider implements ParamConverterProvider {

    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, java.lang.reflect.Type genericType, java.lang.annotation.Annotation[] annotations) {
        if (rawType.equals(UUID.class)) {
            return (ParamConverter<T>) new UUIDParamConverter();
        }
        return null;
    }
}
