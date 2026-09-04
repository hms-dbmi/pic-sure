package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserMetadataMappingUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserMetadataMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    /**
     * Creates metadata mappings from allowlisted request records. The connection is resolved from storage by its business id, so a request
     * body cannot attach its own copy of a connection; the identifier is generated on persist.
     *
     * @param requests the create requests
     * @return the persisted mappings
     * @throws IllegalArgumentException if a referenced connection does not exist
     */
    @Transactional
    public List<UserMetadataMapping> createFrom(List<UserMetadataMappingCreateRequest> requests) {
        List<UserMetadataMapping> mappings = new ArrayList<>(requests.size());
        for (UserMetadataMappingCreateRequest request : requests) {
            mappings.add(
                new UserMetadataMapping().setConnection(resolveConnection(request.connection()))
                    .setGeneralMetadataJsonPath(request.generalMetadataJsonPath()).setAuth0MetadataJsonPath(request.auth0MetadataJsonPath())
            );
        }
        return this.userMetadataMappingRepo.saveAll(mappings);
    }

    /**
     * Applies allowlisted updates to existing metadata mappings. A member left out of the request leaves the stored value unchanged.
     *
     * @param requests the update requests
     * @return the persisted mappings
     * @throws IllegalArgumentException if the mapping or a referenced connection does not exist
     */
    @Transactional
    public List<UserMetadataMapping> updateFrom(List<UserMetadataMappingUpdateRequest> requests) {
        List<UserMetadataMapping> mappings = new ArrayList<>(requests.size());
        for (UserMetadataMappingUpdateRequest request : requests) {
            UserMetadataMapping mapping = this.userMetadataMappingRepo.findById(request.uuid())
                .orElseThrow(() -> new IllegalArgumentException("Cannot find mapping by input UUID: " + request.uuid()));
            if (request.connection() != null) {
                mapping.setConnection(resolveConnection(request.connection()));
            }
            if (request.generalMetadataJsonPath() != null) {
                mapping.setGeneralMetadataJsonPath(request.generalMetadataJsonPath());
            }
            if (request.auth0MetadataJsonPath() != null) {
                mapping.setAuth0MetadataJsonPath(request.auth0MetadataJsonPath());
            }
            mappings.add(mapping);
        }
        return this.userMetadataMappingRepo.saveAll(mappings);
    }

    private Connection resolveConnection(ConnectionRef connectionRef) {
        return this.connectionRepo.findById(connectionRef.id())
            .orElseThrow(() -> new IllegalArgumentException("The following connectionIds do not exist:\n" + connectionRef.id()));
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
