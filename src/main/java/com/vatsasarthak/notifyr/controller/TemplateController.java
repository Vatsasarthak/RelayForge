package com.vatsasarthak.notifyr.controller;

import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.dto.TemplateRequest;
import com.vatsasarthak.notifyr.dto.TemplateResponse;
import com.vatsasarthak.notifyr.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    public ResponseEntity<TemplateResponse> create(@Valid @RequestBody TemplateRequest request) {
        TemplateResponse response = templateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<TemplateResponse>> getAll(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable){
        return ResponseEntity.ok(templateService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateResponse> getById(@PathVariable Long id){
        return ResponseEntity.ok(templateService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TemplateResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody TemplateRequest request){

        return ResponseEntity.ok(templateService.update(id, request));
    }
}
