package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;

import javax.json.JsonObject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Provider
public class LoggerReaderInterceptor implements ReaderInterceptor {

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext interceptorContext)
            throws IOException, WebApplicationException {
        //Capture the request body to be logged when request completes
        InputStream inputStream = interceptorContext.getInputStream();
        String requestContent = IOUtils.toString(inputStream);

        ObjectNode requestNode = (ObjectNode) new ObjectMapper().readTree(requestContent);
        requestNode.remove("resourceCredentials");
        Iterator<String> fields = requestNode.fieldNames();

        List<String> fieldsToRemove = new ArrayList<>();
        while (fields.hasNext()){
            String key = fields.next();
            if (key.contains("BEARER_TOKEN")){
                fieldsToRemove.add(key);
            }
        }

        for (String key : fieldsToRemove){
            requestNode.remove(key);
        }

        //Redact resourceCredentials
  /*      int begin = requestContent.indexOf("resourceCredentials");
        int end = requestContent.indexOf("}", begin);
        String redacted = begin == -1 ? requestContent : requestContent.substring(0, begin-1) + requestContent.substring(end+2);
        //Redact any BEARER_TOKENs
        begin = redacted.indexOf("BEARER_TOKEN");
        while( begin != -1){
            int realbegin = redacted.substring(0, begin).lastIndexOf("\"");
            end = redacted.indexOf("}", begin);
            redacted = realbegin == -1 ? redacted : redacted.substring(0, realbegin-1) + redacted.substring(end+1);
            begin = redacted.indexOf("BEARER_TOKEN");
        }*/

        interceptorContext.setProperty("requestContent", requestNode);
        interceptorContext.setInputStream(new ByteArrayInputStream(requestContent.getBytes()));

        return interceptorContext.proceed();
    }
}
