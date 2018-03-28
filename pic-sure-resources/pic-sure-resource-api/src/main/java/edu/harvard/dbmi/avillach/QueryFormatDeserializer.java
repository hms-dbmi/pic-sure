package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import edu.harvard.dbmi.avillach.domain.QueryFormat;

import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.Map;

public class QueryFormatDeserializer extends JsonDeserializer<QueryFormat> {

/*    private String name;
    private String description;
    private Serializable specification;
    private Serializable[] examples;*/

    @Override
    public QueryFormat deserialize(JsonParser jp, DeserializationContext context) throws IOException{
        JsonNode node = jp.getCodec().readTree(jp);
        QueryFormat qf = new QueryFormat();

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        Serializable[] examples = new Serializable[node.size()];
        int count = 0;
        while (fields.hasNext()){
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase("name")){
                qf.setName(node.get(field.getKey()).asText());
            } else if (field.getKey().equalsIgnoreCase("description")){
                qf.setDescription(node.get(field.getKey()).asText());
            } else if (field.getKey().equalsIgnoreCase("specification")){
                qf.setSpecification(node.get(field.getKey()).asText());
            } else {
                //TODO How to keep this a JsonNode but also serializable???
                examples[count++] = field.toString();
            }
        }
        qf.setExamples(examples);
        return qf;
    }


}
