package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @GetMapping
    public List<ManagementBannerDto> managedBanners() {
        return service.managedBanners();
    }

    @PutMapping("/order")
    public List<ManagementBannerDto> reorder(GatewayUser user, @Valid @RequestBody ReorderBannersRequest request) {
        return service.reorder(request.bannerUuids(), user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementBannerDto publish(GatewayUser user, @Valid @RequestBody PublishBannerRequest request) {
        return service.publish(request, user);
    }

    @PostMapping("/saved")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementBannerDto saveDraft(GatewayUser user, @Valid @RequestBody PublishBannerRequest request) {
        return service.saveDraft(request, user);
    }

    @PutMapping("/{uuid}")
    public ManagementBannerDto update(GatewayUser user, @PathVariable UUID uuid, @Valid @RequestBody PublishBannerRequest request) {
        return service.update(uuid, request, user);
    }

    @PostMapping("/{uuid}/publish")
    public ManagementBannerDto publishDraft(GatewayUser user, @PathVariable UUID uuid, @Valid @RequestBody PublishBannerRequest request) {
        return service.publishDraft(uuid, request, user);
    }

    @PostMapping("/{uuid}/disable")
    public ManagementBannerDto disable(GatewayUser user, @PathVariable UUID uuid) {
        return service.disable(uuid, user);
    }

    @PostMapping("/{uuid}/archive")
    public ArchivedBannerDto archive(GatewayUser user, @PathVariable UUID uuid) {
        return service.archive(uuid, user);
    }
}
