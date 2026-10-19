package com.vatsasarthak.notifyr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignAudienceFilter {

    private List<String> tags;
    private String language;
}
