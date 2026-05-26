package edu.harvard.dbmi.avillach.visualization.model;

import java.util.UUID;

public record HpdsAccessContext(UUID resourceUUID, AccessType accessType) {
}
