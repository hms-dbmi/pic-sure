package edu.harvard.dbmi.avillach.dataupload.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class SiteController {

    @Value("${aws.s3.institution:}")
    private List<String> institutions;

    @GetMapping("/sites")
    public ResponseEntity<List<String>> listSites() {
        return ResponseEntity.ok(institutions);
    }
}
