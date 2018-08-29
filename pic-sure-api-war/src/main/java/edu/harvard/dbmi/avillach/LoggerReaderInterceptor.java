package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import org.apache.commons.io.IOUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

@Provider
public class LoggerReaderInterceptor implements ReaderInterceptor {

    private ObjectMapper mapper = new ObjectMapper();
    private ObjectReader reader = mapper.readerWithView(Views.Default.class).forType(QueryRequest.class);
    private ObjectWriter writer = mapper.writerWithView(Views.Default.class).forType(QueryRequest.class);
    private int count = 0;

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext interceptorContext)
            throws IOException, WebApplicationException {
        //Capture the request body to be logged when request completes
        InputStream inputStream = interceptorContext.getInputStream();
        String requestContent = IOUtils.toString(inputStream);

        //Make sure all resourceCredentials are removed
        QueryRequest request = cleanQueryRequest(requestContent);

        //Return to readable string
        String requestString = mapper.writeValueAsString(request);

        //Put string to context for logging
        interceptorContext.setProperty("requestContent", requestString);

        //Return original body to the request
        interceptorContext.setInputStream(new ByteArrayInputStream(requestContent.getBytes()));

        return interceptorContext.proceed();
    }

    private QueryRequest cleanQueryRequest(String requestString) throws IOException{

        //This will null resourceCredentials due to the use of JsonView
        QueryRequest request = reader.readValue(requestString);

        //The query object might have resourceCredentials in it
        JsonNode node = mapper.valueToTree(request.getQuery());

        if (containsResourceCredentials(node)){
            //Somewhere in here is a resourceCredentials.
            JsonNodeType type = node.getNodeType();

            //First, see what type of JsonNode we're dealing with - it might just be a QueryRequest itself
            if (type.equals(JsonNodeType.OBJECT)){
                try {
                    //If the query was itself a QueryRequest, clean it of resourceCredentials
                    QueryRequest innerQuery = cleanQueryRequest(writer.writeValueAsString(node));
                    //Replace the original query with the cleaned one
                    request.setQuery(innerQuery);
                } catch (JsonProcessingException e) {
                    //Guess it wasn't a QueryRequest, but something inside here was
                    ObjectNode newNode = mapper.createObjectNode();
                    Iterator<Map.Entry<String,JsonNode>> nodes = node.fields();
                    while (nodes.hasNext()){
                        Map.Entry<String,JsonNode> innerNode = nodes.next();
                        //Find which node has resourceCredentials
                        if (containsResourceCredentials(innerNode.getValue())){
                            try {
                                //This had resourceCredentials; clean it
                                QueryRequest testQuery = cleanQueryRequest(writer.writeValueAsString(innerNode));
                                newNode.put(innerNode.getKey(), mapper.valueToTree(testQuery));
                            } catch (JsonProcessingException e2){
                                //TODO
                                //Hmm, maybe it's further inside?  Do we need to keep digging?
                                System.out.println("Was not a queryRequest");
                            }
                        } else {
                            //This node is safe; put it back
                            newNode.put(innerNode.getKey(), innerNode.getValue());
                        }
                    }
                    //Replace the original query with our cleaned node
                    request.setQuery(newNode);
                }
            } else if (type.equals(JsonNodeType.ARRAY)){
                //Make a replacement ArrayNode
                ArrayNode newNode = mapper.createArrayNode();
                Iterator<JsonNode> nodes = node.elements();
                while(nodes.hasNext()){
                    JsonNode next = nodes.next();
                    if (containsResourceCredentials(next)){
                        try {
                            //Is this a QueryRequest?
                            QueryRequest testQuery = cleanQueryRequest(mapper.writeValueAsString(next));
                            //Add the cleaned QueryRequest
                            newNode.add(mapper.valueToTree(testQuery));
                        } catch (JsonProcessingException e) {
                            //TODO Hmm?
                            System.out.println("Not a query request");
                        }
                    } else {
                        //Safe to return
                        newNode.add(next);
                    }
                }
                request.setQuery(newNode);

            }
        }
        return request;
    }

    //Do any subnodes have a resourceCredentials node?
    private boolean containsResourceCredentials(JsonNode node){
        return node!=null && !node.findValues("resourceCredentials").isEmpty();
    }
}
