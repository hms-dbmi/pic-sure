package edu.harvard.hms.dbmi.avillach.auth.model.response;

import java.util.List;

public record ApiKeyPage(List<ApiKeyMetadata> keys, long totalCount, int page, int size) {
}
