package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("connection")
public class ConnectionWebService extends BaseEntityService<Connection> {

    public ConnectionWebService() {
        super(Connection.class);
    }

    @Inject
    ConnectionRepository connectionRepo;

    @Path("{connectionId}")
    @GET
    @Produces("application/json")
    public Response getConnectionById(@PathParam("connectionId") String connectionId) {
        return getEntityById(connectionId,connectionRepo);
    }

    @GET
    @Produces("application/json")
    public Response getAllConnections() {
        return getEntityAll(connectionRepo);
    }

    @Transactional
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response addConnection(List<Connection> connections){
        return addEntity(connections, connectionRepo);
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response updateConnection(List<Connection> connections){
        return updateEntity(connections, connectionRepo);
    }

    @Transactional
    @DELETE
    @Path("/{connectionId}")
    public Response removeById(@PathParam("connectionId") final String connectionId) {
        return removeEntityById(connectionId, connectionRepo);
    }
}
