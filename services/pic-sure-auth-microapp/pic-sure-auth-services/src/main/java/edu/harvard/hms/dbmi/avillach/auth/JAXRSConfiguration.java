package edu.harvard.hms.dbmi.avillach.auth;


import com.fasterxml.jackson.databind.ObjectMapper;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@ApplicationPath("auth")
public class JAXRSConfiguration extends Application {
    public final static ObjectMapper objectMapper = new ObjectMapper();

}
