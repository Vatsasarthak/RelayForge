package com.vatsasarthak.notifyr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "recipientEmail is required")
    @Email(message = "recipientEmail must be a valid email address")
    @Size(max = 255, message = "recipientEmail must be at most 255 characters")
    private String recipientEmail;

    @NotBlank(message = "subject is required")
    @Size(max = 255, message = "subject must be at most 255 characters")
    private String subject;

    @NotBlank(message = "message is required")
    @Size(max = 10000, message = "message must be at most 10000 characters")
    private String message;
}
