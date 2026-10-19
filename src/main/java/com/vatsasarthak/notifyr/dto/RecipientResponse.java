package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.RecipientStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipientResponse {

    private Long id;
    private String name;
    private String email;
    private String phoneNumber;
    private String deviceToken;
    private String preferredLanguage;
    private String timeZone;
    private List<String> tags;
    private RecipientStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
