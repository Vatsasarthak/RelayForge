package com.vatsasarthak.notifyr.controller;

import com.vatsasarthak.notifyr.dto.DeadLetterResponse;
import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.service.DeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ADMIN only (see SecurityConfig): operational endpoints.
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DeadLetterService deadLetterService;

    @GetMapping("/dead-letters")
    public ResponseEntity<PageResponse<DeadLetterResponse>> deadLetters(
            @PageableDefault(size = 20, sort = {"receivedAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(deadLetterService.list(pageable));
    }

    @PostMapping("/dead-letters/{id}/replay")
    public ResponseEntity<DeadLetterResponse> replay(@PathVariable Long id) {
        return ResponseEntity.ok(deadLetterService.replay(id));
    }
}
