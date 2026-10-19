package com.vatsasarthak.notifyr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 255, message = "name must be at most 255 characters")
    private String name;

    @NotBlank(message = "Template subject is required")
    @Size(max = 255, message = "subject must be at most 255 characters")
    private String subject;

    @NotBlank(message = "Template body is required")
    @Size(max = 20000, message = "body must be at most 20000 characters")
    private String body;
}
