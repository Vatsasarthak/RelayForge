package com.vatsasarthak.notifyr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecipientRequest {

    @NotBlank(message = "Recipient name is required")
    @Size(max = 255, message = "name must be at most 255 characters")
    private String name;

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "email must be at most 255 characters")
    private String email;

    // E.164, as required by Twilio, e.g. +15551234567
    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phoneNumber must be in E.164 format, e.g. +15551234567")
    private String phoneNumber;

    @Size(max = 255, message = "deviceToken must be at most 255 characters")
    private String deviceToken;

    @Size(max = 35, message = "preferredLanguage must be at most 35 characters")
    private String preferredLanguage;

    @Size(max = 64, message = "timeZone must be at most 64 characters")
    private String timeZone;

    @Size(max = 50, message = "at most 50 tags")
    private List<@NotBlank @Size(max = 255) String> tags;
}
