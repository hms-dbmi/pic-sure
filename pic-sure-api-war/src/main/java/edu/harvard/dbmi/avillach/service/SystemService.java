package edu.harvard.dbmi.avillach.service;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@Path("/system")
public class SystemService {

    @GET
    @Path("/status")
    @Produces("text/plain")
    public String status() {
        return "RUNNING";
    }
}

