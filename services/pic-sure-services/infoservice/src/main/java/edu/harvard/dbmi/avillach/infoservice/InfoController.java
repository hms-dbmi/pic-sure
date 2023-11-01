package edu.harvard.dbmi.avillach.infoservice;

//import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@Controller
public class InfoController {
    public record ResourceInfo(String name, UUID uuid, List<String> queryFormats){};

    @Value("${pic-sure-resource-uuid}")
    private UUID uuid;

    @PostMapping("/info")
    public ResponseEntity<ResourceInfo> healthCheck(@RequestBody Object ignored) {
        ResourceInfo info = new ResourceInfo("Info Service", uuid, List.of());
//        info.setName("Info Service");
//        info.setId(uuid);
//        info.setQueryFormats(List.of());
        return new ResponseEntity<>(info, HttpStatus.OK);
    }
}
