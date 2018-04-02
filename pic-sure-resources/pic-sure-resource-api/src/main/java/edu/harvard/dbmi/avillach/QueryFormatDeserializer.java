package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import edu.harvard.dbmi.avillach.domain.QueryFormat;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

public class QueryFormatDeserializer extends JsonDeserializer<QueryFormat> {

    //All extra fields are just shoved into examples
    //TODO Not sure this is the format we want this in actually but for now it's readable at least
    @Override
    public QueryFormat deserialize(JsonParser jp, DeserializationContext context) throws IOException{
        JsonNode node = jp.getCodec().readTree(jp);
        QueryFormat qf = new QueryFormat();

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        HashMap<String, JsonNode> extraFields = new HashMap<>();
        Serializable[] examples = new Serializable[1];
        while (fields.hasNext()){
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase("name")){
                qf.setName(node.get(field.getKey()).asText());
            } else if (field.getKey().equalsIgnoreCase("description")){
                qf.setDescription(node.get(field.getKey()).asText());
            } else if (field.getKey().equalsIgnoreCase("specification")){
                qf.setSpecification(node.get(field.getKey()).asText());
            } else {
                extraFields.put(field.getKey(), field.getValue());
            }
        }
        examples[0] = extraFields;
        qf.setExamples(examples);
        return qf;
    }


}
