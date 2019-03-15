package edu.harvard.hms.dbmi.avillach;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class HelloWorldIT {
    private static String endpointUrl;

    @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");
    }



    @Test
    public void testPing() throws Exception {

    }

    @Test
    public void testJsonRoundtrip() throws Exception {
//        List<Object> providers = new ArrayList<Object>();
//        providers.add(new org.codehaus.jackson.jaxrs.JacksonJsonProvider());
//        JsonBean inputBean = new JsonBean();
//        inputBean.setVal1("Maple");
//        WebClient client = WebClient.create(endpointUrl + "/hello/jsonBean", providers);
//        Response r = client.accept("application/json")
//            .type("application/json")
//            .post(inputBean);
//        assertEquals(Response.Status.OK.getStatusCode(), r.getStatus());
//        MappingJsonFactory factory = new MappingJsonFactory();
//        JsonParser parser = factory.createJsonParser((InputStream)r.getEntity());
//        JsonBean output = parser.readValueAs(JsonBean.class);
//        assertEquals("Maple", output.getVal2());
    }
    
}
