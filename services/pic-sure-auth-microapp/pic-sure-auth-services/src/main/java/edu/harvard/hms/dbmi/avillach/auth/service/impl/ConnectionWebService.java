package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ConnectionWebService {

    private final ConnectionRepository connectionRepo;
    private final UserMetadataMappingRepository userMetadataMappingRepo;

    @Autowired
    protected ConnectionWebService(ConnectionRepository connectionRepo, UserMetadataMappingRepository userMetadataMappingRepo) {
        this.connectionRepo = connectionRepo;
        this.userMetadataMappingRepo = userMetadataMappingRepo;
    }

    @Transactional
    public List<Connection> addConnection(List<Connection> connections) throws IllegalArgumentException {
        for (Connection c : connections) {
            if (c.getSubPrefix() == null || c.getRequiredFields() == null || c.getLabel() == null || c.getId() == null) {
                throw new IllegalArgumentException("Id, Label, Subprefix, and RequiredFields cannot be null");
            }
            Optional<Connection> conn = connectionRepo.findById(c.getId());
            if (conn.isPresent()) {
                throw new IllegalArgumentException(
                    "Id must be unique, a connection with id " + c.getId() + " already exists in the database"
                );
            }
        }

        List<Connection> savedConnections = this.connectionRepo.saveAll(connections);

        List<UserMetadataMapping> mappings = savedConnections.stream()
            .map(c -> new UserMetadataMapping().setConnection(c).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"))
            .toList();
        this.userMetadataMappingRepo.saveAll(mappings);

        return savedConnections;
    }

    /**
     * Creates connections from allowlisted request records, then seeds each one's default metadata mapping exactly as
     * {@link #addConnection} does. The identifier is generated on persist.
     *
     * @param requests the create requests
     * @return the persisted connections
     * @throws IllegalArgumentException if a connection with the requested business id already exists
     */
    @Transactional
    public List<Connection> createFrom(List<ConnectionCreateRequest> requests) {
        List<Connection> connections = new ArrayList<>(requests.size());
        for (ConnectionCreateRequest request : requests) {
            if (this.connectionRepo.findById(request.id()).isPresent()) {
                throw new IllegalArgumentException(
                    "Id must be unique, a connection with id " + request.id() + " already exists in the database"
                );
            }
            connections.add(
                new Connection().setId(request.id()).setLabel(request.label()).setSubPrefix(request.subPrefix())
                    .setRequiredFields(request.requiredFields())
            );
        }

        List<Connection> savedConnections = this.connectionRepo.saveAll(connections);
        List<UserMetadataMapping> mappings = savedConnections.stream()
            .map(c -> new UserMetadataMapping().setConnection(c).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"))
            .toList();
        this.userMetadataMappingRepo.saveAll(mappings);
        return savedConnections;
    }

    /**
     * Applies allowlisted updates to existing connections. A member left out of the request leaves the stored value unchanged.
     *
     * @param requests the update requests
     * @return the persisted connections
     * @throws IllegalArgumentException if a request names a connection that does not exist
     */
    @Transactional
    public List<Connection> updateFrom(List<ConnectionUpdateRequest> requests) {
        List<Connection> connections = new ArrayList<>(requests.size());
        for (ConnectionUpdateRequest request : requests) {
            Connection connection = this.connectionRepo.findById(request.uuid())
                .orElseThrow(() -> new IllegalArgumentException("Connection with uuid " + request.uuid() + " not found"));
            if (request.id() != null) {
                connection.setId(request.id());
            }
            if (request.label() != null) {
                connection.setLabel(request.label());
            }
            if (request.subPrefix() != null) {
                connection.setSubPrefix(request.subPrefix());
            }
            if (request.requiredFields() != null) {
                connection.setRequiredFields(request.requiredFields());
            }
            connections.add(connection);
        }
        return this.connectionRepo.saveAll(connections);
    }

    public Connection getConnectionById(String connectionId) {
        return this.connectionRepo.findById(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Connection with id " + connectionId + " not found"));
    }

    public List<Connection> getAllConnections() {
        return this.connectionRepo.findAll();
    }

    public List<Connection> updateConnections(List<Connection> connections) {
        return this.connectionRepo.saveAll(connections);
    }

    @Transactional
    public List<Connection> removeConnectionById(String connectionId) {
        this.connectionRepo.deleteById(connectionId);
        return this.getAllConnections();
    }

    public Connection getConnectionByLabel(String fence) {
        return this.connectionRepo.findByLabel(fence);
    }
}
