package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

@Api
@Path("connection")
public class ConnectionWebService extends BaseEntityService<Connection> {

    public ConnectionWebService() {
        super(Connection.class);
    }

    @Inject
    ConnectionRepository connectionRepo;

    @ApiOperation(value = "GET information of one Connection with the UUID, requires ADMIN or SUPER_ADMIN role")
    @Path("{connectionId}")
    @GET
    @Produces("application/json")
    @RolesAllowed({SUPER_ADMIN, ADMIN})
    public Response getConnectionById(
            @ApiParam(required = true, value="The UUID of the Connection to fetch information about")
            @PathParam("connectionId") String connectionId) {
        return getEntityById(connectionId,connectionRepo);
    }

    @ApiOperation(value = "GET a list of existing Connection, requires SUPER_ADMIN or ADMIN role")
    @GET
    @Produces("application/json")
    @RolesAllowed({SUPER_ADMIN, ADMIN})
    public Response getAllConnections() {
        return getEntityAll(connectionRepo);
    }

    @ApiOperation(value = "POST a list of Connections, requires SUPER_ADMIN role")
    @Transactional
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
    @Path("/")
    public Response addConnection(
            @ApiParam(required = true, value = "A list of Connections in JSON format")
            List<Connection> connections){
        return addEntity(connections);
    }

    @ApiOperation(value = "Update a list of Connections, will only update the fields listed, requires SUPER_ADMIN role")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({SUPER_ADMIN})
    @Path("/")
    public Response updateConnection(
            @ApiParam(required = true, value = "A list of Connection with fields to be updated in JSON format")
            List<Connection> connections){
        return updateEntity(connections, connectionRepo);
    }

    @ApiOperation(value = "DELETE an Connection by Id only if the Connection is not associated by others, requires SUPER_ADMIN role")
    @Transactional
    @DELETE
    @RolesAllowed({SUPER_ADMIN})
    @Path("/{connectionId}")
    public Response removeById(
            @ApiParam(required = true, value = "A valid connection Id")
            @PathParam("connectionId") final String connectionId) {
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
