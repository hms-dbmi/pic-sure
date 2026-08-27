package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/banners")
public class BannerController {

    private final BannerService service;

    public BannerController(BannerService service) {
        this.service = service;
    }

    @GetMapping("/active")
    public List<ActiveBannerDto> activeBanners() {
        return service.activeBanners();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BannerDto publish(GatewayUser user, @Valid @RequestBody PublishBannerRequest request) {
        return service.publish(request, user);
    }
}
