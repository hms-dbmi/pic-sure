package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import edu.harvard.dbmi.avillach.domain.QueryFormat;

import java.io.IOException;
import java.io.Serializable;
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
        HashMap<String, JsonNode> extraFields = new HashMap<>();
        List<HashMap<String, String>> examples = new ArrayList<>();
        while (fields.hasNext()){
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase("name")){
                qf.setName(field.getValue().asText());
            } else if (field.getKey().equalsIgnoreCase("description")){
                qf.setDescription(field.getValue().asText());
            } else if (field.getKey().equalsIgnoreCase("examples")){
                HashMap<String, String> test = mapper.convertValue(field.getValue(), HashMap.class);
                examples.add(test);
            } else {
                extraFields.put(field.getKey(), field.getValue());
            }
        }
        qf.setSpecification(extraFields);
        qf.setExamples(examples.toArray(new Serializable[examples.size()]));
        return qf;
    }


}
