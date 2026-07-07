package edu.harvard.dbmi.avillach.dataupload.site;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SiteInfoController {

    @Autowired
    private SiteListing institutions;

    @GetMapping("/sites")
    public ResponseEntity<SiteListing> listSites() {
        return ResponseEntity.ok(institutions);
    }
}
