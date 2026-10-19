package com.vatsasarthak.notifyr.scheduler;

import com.vatsasarthak.notifyr.entity.Campaign;
import com.vatsasarthak.notifyr.entity.CampaignStatus;
import com.vatsasarthak.notifyr.exception.CampaignAlreadySentException;
import com.vatsasarthak.notifyr.repository.CampaignRepository;
import com.vatsasarthak.notifyr.service.CampaignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignSchedulerTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignService campaignService;

    @InjectMocks
    private CampaignScheduler scheduler;

    @Test
    void sendsEveryDueScheduledCampaignEvenIfOneFails() {
        Campaign first = Campaign.builder().id(1L).status(CampaignStatus.SCHEDULED).build();
        Campaign second = Campaign.builder().id(2L).status(CampaignStatus.SCHEDULED).build();
        when(campaignRepository.findByStatusAndScheduledTimeLessThanEqual(eq(CampaignStatus.SCHEDULED),
                any(LocalDateTime.class))).thenReturn(List.of(first, second));
        // e.g. a manual send claimed campaign 1 a moment earlier
        when(campaignService.send(1L)).thenThrow(new CampaignAlreadySentException(1L, CampaignStatus.RUNNING));

        scheduler.processScheduledCampaigns();

        verify(campaignService).send(1L);
        verify(campaignService).send(2L);
    }

    @Test
    void doesNothingWhenNoCampaignIsDue() {
        when(campaignRepository.findByStatusAndScheduledTimeLessThanEqual(eq(CampaignStatus.SCHEDULED),
                any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.processScheduledCampaigns();

        verify(campaignService, never()).send(anyLong());
    }
}
