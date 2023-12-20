package edu.harvard.dbmi.avillach.dataupload.status;

import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

@Controller
public class StatusController {

    @Autowired
    StatusService statusService;

    @GetMapping("/status/server")
    public ResponseEntity<String> getServerStatus() {
        return ResponseEntity.ok(statusService.getClientStatus());
    }

    @GetMapping("/status/{queryId}")
    public ResponseEntity<DataUploadStatuses> getUploadStatus(@PathVariable("queryId") String queryId) {
        return statusService.getStatus(queryId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status/{queryId}/approve")
    public ResponseEntity<DataUploadStatuses> getUploadStatus(
            @PathVariable("queryId") String queryId,
            @RequestParam("date") String approvalDate
    ) {
        return statusService.approve(queryId, parseDate(approvalDate))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.internalServerError().build());
    }

    private LocalDate parseDate(@Nullable String date) {
        try {
            return LocalDate.parse(Objects.requireNonNull(date));
        } catch (DateTimeParseException | NullPointerException e) {
            return LocalDate.now();
        }
    }
}
