package edu.harvard.dbmi.avillach.logging.model;

import java.util.List;
import java.util.UUID;

public record InfoResponse(UUID id, String name, List<String> queryFormats) {
}
