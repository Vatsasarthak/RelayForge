package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.CampaignDispatchMessage;
import com.vatsasarthak.notifyr.dto.CampaignRequest;
import com.vatsasarthak.notifyr.dto.CampaignResponse;
import com.vatsasarthak.notifyr.dto.CampaignStatsResponse;
import com.vatsasarthak.notifyr.dto.CampaignSummary;
import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.entity.*;
import com.vatsasarthak.notifyr.exception.*;
import com.vatsasarthak.notifyr.repository.CampaignRepository;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.repository.RecipientRepository;
import com.vatsasarthak.notifyr.repository.TemplateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    private static final List<CampaignStatus> EDITABLE_STATUSES = List.of(CampaignStatus.DRAFT, CampaignStatus.SCHEDULED);
    private static final int MAX_SUBJECT_LENGTH = 255;

    private final CampaignRepository campaignRepository;
    private final TemplateRepository templaterepository;
    private final RecipientRepository recipientRepository;
    private final NotificationRepository notificationRepository;
    private final TemplateService templateService;
    private final OutboxService outboxService;
    private final NotificationLifecycleService lifecycleService;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    @Value("${notification.campaign-dispatch.exchange}")
    private String campaignDispatchExchangeName;

    @Value("${notification.campaign-dispatch.routing-key}")
    private String campaignDispatchRoutingKey;

    @Transactional
    public CampaignResponse create(CampaignRequest request, String createdBy) {

        templaterepository.findById(request.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(request.getTemplateId()));

        validateTargeting(request); // validate targeting criteria

        CampaignStatus initialStatus = resolveInitialStatus(request);

        validateRecipientsExist(request);

        Campaign campaign = Campaign.builder()
                .name(request.getName())
                .description(request.getDescription())
                .channel(request.getChannel())
                .templateId(request.getTemplateId())
                .recipientIds(request.getRecipientIds() != null ? new ArrayList<>(request.getRecipientIds()) : new ArrayList<>())
                .audienceTags(request.getAudienceTags() != null ? new ArrayList<>(request.getAudienceTags()) : new ArrayList<>())
                .audienceLanguage(request.getAudienceLanguage())
                .templateVariables(request.getTemplateVariables() != null
                        ? new HashMap<>(request.getTemplateVariables()) : new HashMap<>())
                .scheduledTime(request.getScheduledTime())
                .status(initialStatus)
                .createdBy(createdBy)
                .build();

        campaign = campaignRepository.save(campaign);

        meterRegistry.counter("campaigns_created_total", "channel"
                , campaign.getChannel().toString()).increment();

        return mapToResponse(campaign);
    }

    @Transactional(readOnly = true)
    public CampaignResponse getById(Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));
        return mapToResponse(campaign);
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignResponse> getAll(Pageable pageable){
        return PageResponse.of(campaignRepository.findAllSummaries(pageable), this::mapSummaryToResponse);
    }

    // delivery progress of a campaign: its notifications counted by status
    @Transactional(readOnly = true)
    public CampaignStatsResponse getStats(Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        Map<NotificationStatus, Long> byStatus = new EnumMap<>(NotificationStatus.class);
        for (NotificationStatus status : NotificationStatus.values()) {
            byStatus.put(status, 0L);
        }
        long total = 0;
        for (Object[] row : notificationRepository.countByStatusForCampaign(id)) {
            long count = (Long) row[1];
            byStatus.put((NotificationStatus) row[0], count);
            total += count;
        }
        return new CampaignStatsResponse(id, campaign.getStatus(), total, byStatus);
    }

    @Transactional
    public CampaignResponse update(Long id, CampaignRequest request){

        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        // a campaign that is running or finished must not change underneath its notifications
        if(!EDITABLE_STATUSES.contains(campaign.getStatus())){
            throw new CampaignNotEditableException(id, campaign.getStatus());
        }

        templaterepository.findById(request.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(request.getTemplateId()));

        validateTargeting(request);

        CampaignStatus newStatus = resolveInitialStatus(request);

        validateRecipientsExist(request);

        campaign.setName(request.getName());
        campaign.setDescription(request.getDescription());
        campaign.setChannel(request.getChannel());
        campaign.setTemplateId(request.getTemplateId());
        campaign.setRecipientIds(request.getRecipientIds() != null
                ? new ArrayList<>(request.getRecipientIds()) : new ArrayList<>());
        campaign.setAudienceTags(request.getAudienceTags() != null
                ? new ArrayList<>(request.getAudienceTags()) : new ArrayList<>());
        campaign.setAudienceLanguage(request.getAudienceLanguage());
        campaign.setTemplateVariables(
                request.getTemplateVariables() != null ?
                new HashMap<>(request.getTemplateVariables()) : new HashMap<>());
        campaign.setScheduledTime(request.getScheduledTime());
        campaign.setStatus(newStatus);

        campaign = campaignRepository.save(campaign);
        return mapToResponse(campaign);
    }

    @Transactional
    public void delete(Long id){
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        // deleting a campaign mid fan-out would make the dispatch fail half way
        if(campaign.getStatus() == CampaignStatus.RUNNING){
            throw new CampaignNotEditableException(id, campaign.getStatus());
        }
        campaignRepository.delete(campaign);
    }

    // Validates and triggers the dispatch. The status change and the dispatch message are committed
    // together (outbox), so a campaign can neither be RUNNING without a message nor dispatched twice.
    @Transactional
    public CampaignResponse send(Long campaignId){

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        CampaignStatus previousStatus = campaign.getStatus();

        if(!EDITABLE_STATUSES.contains(previousStatus)){
            throw new CampaignAlreadySentException(campaign.getId(), previousStatus);
        }

        // claim the campaign atomically: only one caller can move it out of DRAFT/SCHEDULED
        int claimed = campaignRepository.transitionStatus(
                campaignId, List.of(previousStatus), CampaignStatus.RUNNING, LocalDateTime.now());

        if(claimed == 0){
            CampaignStatus currentStatus = campaignRepository.findById(campaignId)
                    .map(Campaign::getStatus)
                    .orElseThrow(() -> new CampaignNotFoundException(campaignId));
            throw new CampaignAlreadySentException(campaignId, currentStatus);
        }

        outboxService.enqueue(OutboxEvent.AGGREGATE_CAMPAIGN, campaignId, campaignDispatchExchangeName,
                campaignDispatchRoutingKey, new CampaignDispatchMessage(campaignId));

        log.info("Campaign {} : dispatch triggered", campaignId);

        return mapToResponse(campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId)));
    }

    // Recovery: re-publishes the dispatch message of campaigns stuck in RUNNING. Safe to repeat because the
    // fan-out only runs while the campaign is RUNNING and holds its row lock.
    @Transactional
    public int redispatchStuckCampaigns(Duration staleAfter, int limit){
        List<Long> ids = campaignRepository.lockStuckRunning(LocalDateTime.now().minus(staleAfter), limit);
        for(Long id : ids){
            Campaign campaign = campaignRepository.findById(id).orElseThrow();
            campaign.setUpdatedAt(LocalDateTime.now());
            outboxService.enqueue(OutboxEvent.AGGREGATE_CAMPAIGN, id, campaignDispatchExchangeName,
                    campaignDispatchRoutingKey, new CampaignDispatchMessage(id));
            log.warn("Campaign {} : stuck in RUNNING, dispatch re-published", id);
        }
        return ids.size();
    }

    // Called by the dispatch worker - handles the fan-out in ONE transaction: the per-recipient
    // notifications, their queue messages (outbox) and the COMPLETED status commit together or not at
    // all. The campaign row is locked, so a duplicate dispatch message waits and then finds COMPLETED.
    public void processDispatch(Long campaignId){
        try{
            transactionTemplate.executeWithoutResult(status -> fanOut(campaignId));
        }catch(CampaignNotFoundException e){
            throw e;
        }catch(Exception e){
            campaignRepository.transitionStatus(
                    campaignId, List.of(CampaignStatus.RUNNING), CampaignStatus.FAILED, LocalDateTime.now());
            log.error("Campaign {} : dispatch failed due to error: {}", campaignId, e.getMessage());
            throw e;
        }
    }

    private void fanOut(Long campaignId){

        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        if(campaign.getStatus() != CampaignStatus.RUNNING){
            log.warn("Campaign {} : dispatch ignored because status is {} (expected RUNNING)",
                    campaignId, campaign.getStatus());
            return;
        }

        // fetch template for this campaign
        Template template = templaterepository.findById(campaign.getTemplateId())
                .orElseThrow(() -> new TemplateNotFoundException(campaign.getTemplateId()));

        // find all recipients based on targeting criteria
        List<Recipient> targetRecipients = resolveRecipients(campaign);

        int queued = 0;
        int skipped = 0;

        for (Recipient recipient : targetRecipients) {

            Map<String, String> variables = new HashMap<>(campaign.getTemplateVariables());
            variables.put("name", recipient.getName());
            variables.put("email", recipient.getEmail());

            String renderedSubject = truncate(templateService.render(template.getSubject(), variables));
            String renderedBody = templateService.render(template.getBody(), variables);

            Notification.NotificationBuilder notificationBuilder = Notification.builder()
                    .subject(renderedSubject)
                    .message(renderedBody)
                    .status(NotificationStatus.QUEUED)
                    .channel(campaign.getChannel())
                    .campaignId(campaignId)
                    .owner(campaign.getCreatedBy());

            String address = switch(campaign.getChannel()) {
                case SMS -> recipient.getPhoneNumber();
                case PUSH -> recipient.getDeviceToken();
                default -> recipient.getEmail();
            };

            if(campaign.getChannel() == NotificationChannel.EMAIL){
                notificationBuilder.recipientEmail(recipient.getEmail());
            }else if(campaign.getChannel() == NotificationChannel.SMS){
                notificationBuilder.recipientPhone(recipient.getPhoneNumber());
            }else if(campaign.getChannel() == NotificationChannel.PUSH){
                notificationBuilder.recipientDeviceToken(recipient.getDeviceToken());
            }

            // a recipient without an address for this channel can never succeed, so do not queue it for retries
            if(address == null || address.isBlank()){
                notificationBuilder
                        .status(NotificationStatus.FAILED)
                        .failureReason("Recipient " + recipient.getId() + " has no "
                                + describeAddress(campaign.getChannel()));
                notificationRepository.save(notificationBuilder.build());
                skipped++;
                log.warn("Campaign {} : recipient {} skipped, no {}",
                        campaignId, recipient.getId(), describeAddress(campaign.getChannel()));
                continue;
            }

            Notification notification = notificationRepository.save(notificationBuilder.build());
            lifecycleService.enqueueDelivery(notification);
            queued++;
        }

        campaign.setStatus(CampaignStatus.COMPLETED);

        log.info("Campaign {} : fan-out finished - {} notification(s) queued, {} skipped (missing contact)"
                , campaignId, queued, skipped);
    }

    private static String describeAddress(NotificationChannel channel){
        return switch(channel) {
            case SMS -> "phone number";
            case PUSH -> "device token";
            default -> "email address";
        };
    }

    private static String truncate(String subject) {
        if (subject == null || subject.length() <= MAX_SUBJECT_LENGTH) {
            return subject;
        }
        return subject.substring(0, MAX_SUBJECT_LENGTH - 3) + "...";
    }

    private List<Recipient> resolveRecipients(Campaign campaign) {

        List<Recipient> candidates;
        boolean hasExplicitIds = campaign.getRecipientIds() != null
                && !campaign.getRecipientIds().isEmpty();

        if(hasExplicitIds){
            // a recipient deleted after the campaign was created is skipped instead of failing the whole campaign
            candidates = campaign.getRecipientIds().stream()
                    .map(id -> {
                        Optional<Recipient> recipient = recipientRepository.findById(id);
                        if(recipient.isEmpty()){
                            log.warn("Campaign {} : recipient {} no longer exists, skipping", campaign.getId(), id);
                        }
                        return recipient;
                    })
                    .flatMap(Optional::stream)
                    .toList();
        }else{
            candidates = recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED);

            boolean hasTags = campaign.getAudienceTags() != null
                    && !campaign.getAudienceTags().isEmpty();
            boolean hasLanguages = campaign.getAudienceLanguage() != null
                    && !campaign.getAudienceLanguage().isEmpty();

            if(hasTags){
                candidates = candidates.stream()
                        .filter(r -> r.getTags() != null && !r.getTags().isEmpty())
                        .filter(r -> r.getTags().stream().anyMatch(campaign.getAudienceTags()::contains))
                        .toList();
            }
            if(hasLanguages){
                candidates = candidates.stream()
                        .filter(r -> campaign.getAudienceLanguage().equals(r.getPreferredLanguage()))
                        .toList();
            }
        }

        // exclude unsubscribed recipients always
        return candidates.stream()
                .filter(r -> r.getStatus() != RecipientStatus.UNSUBSCRIBED)
                .toList();
    }

    private CampaignStatus resolveInitialStatus(CampaignRequest request) {
        if(request.getScheduledTime() == null){
            return CampaignStatus.DRAFT;
        }
        if(!request.getScheduledTime().isAfter(LocalDateTime.now())){
            throw new InvalidScheduleTimeException();
        }
        return CampaignStatus.SCHEDULED;
    }

    private void validateRecipientsExist(CampaignRequest request) {
        if (request.getRecipientIds() != null) {
            for (Long recipientId : request.getRecipientIds()) {
                recipientRepository.findById(recipientId)
                        .orElseThrow(() -> new RecipientNotFoundException(recipientId));
            }
        }
    }

    private void validateTargeting(CampaignRequest request) {
        boolean hasRecipientIds = request.getRecipientIds() != null && !request.getRecipientIds().isEmpty();
        boolean hasAudienceTags = request.getAudienceTags() != null && !request.getAudienceTags().isEmpty();
        boolean hasAudienceLanguage = request.getAudienceLanguage() != null
                && !request.getAudienceLanguage().isEmpty();

        if (!hasRecipientIds && !hasAudienceTags && !hasAudienceLanguage) {
            throw new InvalidCampaignTargetException();
        }
    }

    // copies the lazy collections so the response is usable after the persistence context closes
    private CampaignResponse mapToResponse(Campaign campaign) {
        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getDescription(),
                campaign.getChannel(),
                campaign.getTemplateId(),
                new ArrayList<>(campaign.getRecipientIds()),
                new ArrayList<>(campaign.getAudienceTags()),
                campaign.getAudienceLanguage(),
                new HashMap<>(campaign.getTemplateVariables()),
                campaign.getStatus(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt(),
                campaign.getScheduledTime()
        );
    }

    private CampaignResponse mapSummaryToResponse(CampaignSummary summary) {
        return new CampaignResponse(
                summary.getId(),
                summary.getName(),
                summary.getDescription(),
                summary.getChannel(),
                summary.getTemplateId(),
                List.of(),
                List.of(),
                summary.getAudienceLanguage(),
                Map.of(),
                summary.getStatus(),
                summary.getCreatedAt(),
                summary.getUpdatedAt(),
                summary.getScheduledTime()
        );
    }

}
