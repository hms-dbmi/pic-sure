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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Banners", description = "Site announcements and their publication lifecycle")
@RestController
@RequestMapping("/banners")
public class BannerController {

    private final BannerService service;

    public BannerController(BannerService service) {
        this.service = service;
    }

    @Operation(summary = "List currently active banners")
    @ApiResponse(responseCode = "200", description = "Active banners in display order")
    @GetMapping("/active")
    public List<ActiveBannerDto> activeBanners() {
        return service.activeBanners();
    }

    @Operation(summary = "List banners available for management")
    @ApiResponse(responseCode = "200", description = "Management banners")
    @GetMapping
    public List<ManagementBannerDto> managedBanners() {
        return service.managedBanners();
    }

    @Operation(summary = "Reorder banners")
    @ApiResponse(responseCode = "200", description = "Banners in their updated order")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @PutMapping("/order")
    public List<ManagementBannerDto> reorder(GatewayUser user, @Valid @RequestBody ReorderBannersRequest request) {
        return service.reorder(request.bannerUuids(), user);
    }

    @Operation(summary = "Publish a banner")
    @ApiResponse(responseCode = "201", description = "The published banner")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementBannerDto publish(GatewayUser user, @Valid @RequestBody PublishBannerRequest request) {
        return service.publish(request, user);
    }

    @Operation(summary = "Save a banner draft")
    @ApiResponse(responseCode = "201", description = "The saved draft")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @PostMapping("/saved")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementBannerDto saveDraft(GatewayUser user, @Valid @RequestBody PublishBannerRequest request) {
        return service.saveDraft(request, user);
    }

    @Operation(summary = "Update a banner")
    @ApiResponse(responseCode = "200", description = "The updated banner")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @ApiResponse(responseCode = "404", description = "Banner not found")
    @ApiResponse(responseCode = "409", description = "The banner lifecycle does not allow this operation")
    @PutMapping("/{uuid}")
    public ManagementBannerDto update(GatewayUser user, @PathVariable UUID uuid, @Valid @RequestBody PublishBannerRequest request) {
        return service.update(uuid, request, user);
    }

    @Operation(summary = "Publish a saved draft")
    @ApiResponse(responseCode = "200", description = "The published banner")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @ApiResponse(responseCode = "404", description = "Banner not found")
    @ApiResponse(responseCode = "409", description = "The banner lifecycle does not allow this operation")
    @PostMapping("/{uuid}/publish")
    public ManagementBannerDto publishDraft(GatewayUser user, @PathVariable UUID uuid, @Valid @RequestBody PublishBannerRequest request) {
        return service.publishDraft(uuid, request, user);
    }

    @Operation(summary = "Disable a banner")
    @ApiResponse(responseCode = "200", description = "The disabled banner")
    @ApiResponse(responseCode = "404", description = "Banner not found")
    @ApiResponse(responseCode = "409", description = "The banner lifecycle does not allow this operation")
    @PostMapping("/{uuid}/disable")
    public ManagementBannerDto disable(GatewayUser user, @PathVariable UUID uuid) {
        return service.disable(uuid, user);
    }

    @Operation(summary = "Archive a banner")
    @ApiResponse(responseCode = "200", description = "The archived banner")
    @ApiResponse(responseCode = "404", description = "Banner not found")
    @ApiResponse(responseCode = "409", description = "The banner lifecycle does not allow this operation")
    @PostMapping("/{uuid}/archive")
    public ArchivedBannerDto archive(GatewayUser user, @PathVariable UUID uuid) {
        return service.archive(uuid, user);
    }

    @Operation(summary = "Restore a banner as a new publication")
    @ApiResponse(responseCode = "201", description = "The restored banner")
    @ApiResponse(responseCode = "400", description = "Invalid banner request")
    @ApiResponse(responseCode = "404", description = "Banner not found")
    @ApiResponse(responseCode = "409", description = "The banner lifecycle does not allow this operation")
    @PostMapping("/{uuid}/restore")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementBannerDto restore(
        GatewayUser user, @PathVariable UUID uuid, @Valid @RequestBody PublishBannerRequest request
    ) {
        return service.restore(uuid, request, user);
    }
}
