package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConnectionWebService {

    private final ConnectionRepository connectionRepo;

    @Autowired
    protected ConnectionWebService(ConnectionRepository connectionRepo) {
        this.connectionRepo = connectionRepo;
    }

    @Transactional
    public List<Connection> addConnection(List<Connection> connections) throws IllegalArgumentException {
        for (Connection c : connections){
            if (c.getSubPrefix() == null || c.getRequiredFields() == null || c.getLabel() == null || c.getId() == null){
                throw new IllegalArgumentException("Id, Label, Subprefix, and RequiredFields cannot be null");
            }
            Optional<Connection> conn = connectionRepo.findById(c.getId());
            if (conn.isPresent()){
                throw new IllegalArgumentException("Id must be unique, a connection with id " + c.getId() + " already exists in the database");
            }
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
