package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ReorderBannersRequest(@NotNull List<UUID> bannerUuids) {
}
