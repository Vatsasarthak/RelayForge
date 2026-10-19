package com.vatsasarthak.notifyr.controller;

import com.vatsasarthak.notifyr.dto.NotificationRequest;
import com.vatsasarthak.notifyr.dto.NotificationResponse;
import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.security.CurrentUser;
import com.vatsasarthak.notifyr.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request,
                                                     @RequestHeader(value="Idempotency-Key",
                                                             required=false)
                                                     @Size(max = 255, message = "Idempotency-Key must be at most 255 characters")
                                                     String idempotencyKey) {
        NotificationResponse response = notificationService.sendNotification(
                request, CurrentUser.get().username(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/notifications/" + response.getId()))
                .body(response);
    }

    // the caller's notifications (all notifications for ADMIN), newest first
    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @RequestParam(required = false) NotificationStatus status,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(notificationService.list(CurrentUser.get(), status, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.getNotification(id, CurrentUser.get()));
    }

    // ADMIN: put a FAILED notification back into the delivery queue
    @PostMapping("/{id}/retry")
    public ResponseEntity<NotificationResponse> retry(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(notificationService.retry(id));
    }


    @GetMapping("/health")
    public String healthCheck() {
        return "Notification service is up and running!";
    }
}
