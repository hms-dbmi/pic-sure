package edu.harvard.dbmi.avillach.dictionary.info;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
public class InfoController {

    @PostMapping("/info")
    public ResponseEntity<InfoResponse> getInfo(@RequestBody Object ignored) {
        return ResponseEntity.ok(new InfoResponse(":)"));
    }
}
