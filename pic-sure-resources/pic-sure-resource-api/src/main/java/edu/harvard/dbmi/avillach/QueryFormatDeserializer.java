package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import edu.harvard.dbmi.avillach.domain.QueryFormat;

import java.io.IOException;
import java.util.*;

public class QueryFormatDeserializer extends JsonDeserializer<QueryFormat> {

    private final static ObjectMapper mapper = new ObjectMapper();
    //All extra fields are just shoved into specification
    //TODO Not sure this is the format we want this in actually but for now it's readable at least
    @Override
    public QueryFormat deserialize(JsonParser jp, DeserializationContext context) throws IOException{
        JsonNode node = jp.getCodec().readTree(jp);
        QueryFormat qf = new QueryFormat();

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        HashMap<String, Object> extraFields = new HashMap<>();
        List<Map<String, Object>> examples = new ArrayList<>();
        while (fields.hasNext()){
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase("name")){
                qf.setName(field.getValue().asText());
            } else if (field.getKey().equalsIgnoreCase("description")){
                qf.setDescription(field.getValue().asText());
            } else if (field.getKey().equalsIgnoreCase("examples")){
            	    List<Map<String, Object>> test = mapper.convertValue(field.getValue(), ArrayList.class);
                examples = test;
            } else {
                extraFields.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
            }
        }
        if (!extraFields.isEmpty()){
            qf.setSpecification(extraFields);
        }
        if (!examples.isEmpty()){
            qf.setExamples(examples);
        }
        return qf;
    }


}
