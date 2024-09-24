package edu.harvard.dbmi.avillach.dictionary.info;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@Controller
public class InfoController {

    @PostMapping("/info")
    public ResponseEntity<InfoResponse> getInfo(@RequestBody Object ignored) {
        return ResponseEntity.ok(new InfoResponse(UUID.nameUUIDFromBytes(":)".getBytes()), ":)", List.of()));
    }
}
