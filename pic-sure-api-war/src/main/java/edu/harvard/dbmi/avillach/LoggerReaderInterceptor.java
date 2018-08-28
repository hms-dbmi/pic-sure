package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Provider
public class LoggerReaderInterceptor implements ReaderInterceptor {

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext interceptorContext)
            throws IOException, WebApplicationException {
        //Capture the request body to be logged when request completes
        InputStream inputStream = interceptorContext.getInputStream();
        String requestContent = IOUtils.toString(inputStream);

        JsonNode requestNode = new ObjectMapper().readTree(requestContent);
        //Redact any credentials
        Set<Map.Entry<String, JsonNode>> filtered = StreamSupport.stream(Spliterators.spliteratorUnknownSize(requestNode.fields(), Spliterator.NONNULL), false)
                .filter(s -> !s.getKey().equals("resourceCredentials") && !s.getKey().contains("BEARER_TOKEN"))
                .collect(Collectors.toSet());

        interceptorContext.setProperty("requestContent", filtered);
        //Return original body to the request
        interceptorContext.setInputStream(new ByteArrayInputStream(requestContent.getBytes()));

        return interceptorContext.proceed();
    }
}
