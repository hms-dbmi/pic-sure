package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import java.util.List;

public record AuthorizationFilter(
        String conceptPath,
        List<String> values
) {
}
