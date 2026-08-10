package edu.harvard.dbmi.avillach.logging.web;

import edu.harvard.dbmi.avillach.logging.model.InfoResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class InfoController {

    private final InfoResponse response =
        new InfoResponse(UUID.nameUUIDFromBytes(":)".getBytes(StandardCharsets.UTF_8)), "Logging Service", List.of());

    @PostMapping("/info")
    public InfoResponse info() {
        return response;
    }
}
