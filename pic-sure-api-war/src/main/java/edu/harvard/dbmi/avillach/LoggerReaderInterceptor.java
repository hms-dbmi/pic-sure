package edu.harvard.dbmi.avillach;

import org.apache.commons.io.IOUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

//@Provider
public class LoggerReaderInterceptor implements ReaderInterceptor {

    private final String sentinel = "RESOURCE_CREDENTIALS_REDACTED";

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext interceptorContext)
            throws IOException, WebApplicationException {
        //Capture the request body to be logged when request completes
        try (InputStream inputStream = interceptorContext.getInputStream()) {

            String requestContent = IOUtils.toString(inputStream, "UTF-8");

            //Totally manually redact resourceCredentials from this string
            String requestString = requestContent;
            while (requestString.contains("resourceCredentials")){
                int rcBegin = requestString.indexOf("resourceCredentials");
                int startBracket = requestString.indexOf("{", rcBegin);
                int bracketCount = 0;
                int endBracket = -1;
                for (int i = startBracket; i < requestString.length(); i++){
                    if (requestString.charAt(i) == '{'){
                        bracketCount++;
                    } if (requestString.charAt(i) == '}'){
                        bracketCount--;
                    }
                    if (bracketCount < 1){
                        endBracket = i;
                        break;
                    }
                }
                requestString = requestString.substring(0, rcBegin-1) +sentinel+ requestString.substring(endBracket+1);
            }

            //Put string to context for logging
            interceptorContext.setProperty("requestContent", requestString);

            //Return original body to the request
            interceptorContext.setInputStream(new ByteArrayInputStream(requestContent.getBytes()));

            return interceptorContext.proceed();
        }
    }

}
