package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <p>Provides business logic for UserMetadataMapping endpoint.</p>
 */
@Service
public class UserMetadataMappingService {

    private final UserMetadataMappingRepository userMetadataMappingRepo;

    private final ConnectionRepository connectionRepo;

    @Autowired
    public UserMetadataMappingService(UserMetadataMappingRepository userMetadataMappingRepo, ConnectionRepository connectionRepo) {
        this.userMetadataMappingRepo = userMetadataMappingRepo;
        this.connectionRepo = connectionRepo;
    }

    public List<UserMetadataMapping> getAllMappingsForConnection(Connection connection) {
        return userMetadataMappingRepo.findByConnection(connection);
    }

    @Transactional
    public List<UserMetadataMapping> addMappings(List<UserMetadataMapping> mappings) {
        StringBuilder errorMessage = new StringBuilder("The following connectionIds do not exist:\n");
        boolean error = false;
        for (UserMetadataMapping umm : mappings) {
            Optional<Connection> c = connectionRepo.findById(umm.getConnection().getId());
            if (c.isEmpty()) {
                error = true;
                errorMessage.append(umm.getConnection().getId()).append("\n");
            } else {
                umm.setConnection(c.get());
            }
        }

        if (error) {
            throw new IllegalArgumentException(errorMessage.toString());
        }

        return this.userMetadataMappingRepo.saveAll(mappings);
    }

    public List<UserMetadataMapping> getAllMappings() {
        return userMetadataMappingRepo.findAll();
    }

    public Connection getAllMappingsForConnection(String connectionId) {
        return this.connectionRepo.findById(connectionId).orElseThrow(() -> new IllegalArgumentException("Connection not found"));
    }

    public List<UserMetadataMapping> updateUserMetadataMappings(List<UserMetadataMapping> mappings) {
        return this.userMetadataMappingRepo.saveAll(mappings);
    }

    public List<UserMetadataMapping> removeMetadataMappingByIdAndRetrieveAll(String mappingId) {
        this.userMetadataMappingRepo.deleteById(UUID.fromString(mappingId));
        return this.userMetadataMappingRepo.findAll();
    }
}
