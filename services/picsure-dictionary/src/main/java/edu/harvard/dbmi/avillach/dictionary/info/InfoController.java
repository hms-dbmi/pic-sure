package edu.harvard.dbmi.avillach.dictionary.info;

import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class InfoController {

    /**
     * The dictionary is not a queryable resource -- it advertises no query formats. The names below are the ones this service claims to
     * accept; each is widened into the shared {@link QueryFormat} record so every PIC-SURE /info speaks one shape.
     */
    private static final List<String> QUERY_FORMAT_NAMES = List.of();

    private static final ResourceInfo INFO = new ResourceInfo(
        UUID.nameUUIDFromBytes(":)".getBytes()), ":)",
        QUERY_FORMAT_NAMES.stream().map(name -> new QueryFormat(name, "", Map.of(), List.of())).toList()
    );

    @AuditEvent(type = "OTHER", action = "info")
    @PostMapping("/info")
    public ResponseEntity<ResourceInfo> getInfo() {
        return ResponseEntity.ok(INFO);
    }
}
