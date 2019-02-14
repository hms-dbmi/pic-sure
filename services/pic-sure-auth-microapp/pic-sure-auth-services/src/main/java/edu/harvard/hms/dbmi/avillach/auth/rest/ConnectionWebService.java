package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.*;

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
    @RolesAllowed({SYSTEM, SUPER_ADMIN, ADMIN})
    public Response getConnectionById(@PathParam("connectionId") String connectionId) {
        return getEntityById(connectionId,connectionRepo);
    }

    @GET
    @Produces("application/json")
    @RolesAllowed({SYSTEM, SUPER_ADMIN, ADMIN})
    public Response getAllConnections() {
        return getEntityAll(connectionRepo);
    }

    @Transactional
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SYSTEM, SUPER_ADMIN})
    @Path("/")
    public Response addConnection(List<Connection> connections){
        return addEntity(connections);
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SYSTEM, SUPER_ADMIN})
    @Path("/")
    public Response updateConnection(List<Connection> connections){
        return updateEntity(connections, connectionRepo);
    }

    @Transactional
    @DELETE
    @RolesAllowed({SYSTEM, SUPER_ADMIN})
    @Path("/{connectionId}")
    public Response removeById(@PathParam("connectionId") final String connectionId) {
        return removeEntityById(connectionId, connectionRepo);
    }

    private Response addEntity(List<Connection> connections){
        for (Connection c : connections){
            if (c.getSubPrefix() == null || c.getRequiredFields() == null || c.getLabel() == null || c.getId() == null){
                return PICSUREResponse.protocolError("Id, Label, Subprefix, and RequiredFields cannot be null");
            }
            Connection conn = connectionRepo.findConnectionById(c.getId());
            if (conn != null){
                return PICSUREResponse.protocolError("Id must be unique, a connection with id " + c.getId() + " already exists in the database");
            }
        }
        return addEntity(connections, connectionRepo);
    }
}
