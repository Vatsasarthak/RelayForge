package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.CampaignDispatchMessage;
import com.vatsasarthak.notifyr.dto.CampaignRequest;
import com.vatsasarthak.notifyr.entity.Campaign;
import com.vatsasarthak.notifyr.entity.CampaignStatus;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.entity.NotificationStatus;
import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.entity.Recipient;
import com.vatsasarthak.notifyr.entity.RecipientStatus;
import com.vatsasarthak.notifyr.entity.Template;
import com.vatsasarthak.notifyr.exception.CampaignAlreadySentException;
import com.vatsasarthak.notifyr.exception.CampaignNotEditableException;
import com.vatsasarthak.notifyr.exception.InvalidScheduleTimeException;
import com.vatsasarthak.notifyr.exception.TemplateNotFoundException;
import com.vatsasarthak.notifyr.repository.CampaignRepository;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.repository.RecipientRepository;
import com.vatsasarthak.notifyr.repository.TemplateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CampaignServiceTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock private TemplateRepository templateRepository;
    @Mock private RecipientRepository recipientRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TemplateService templateService;
    @Mock private OutboxService outboxService;
    @Mock private NotificationLifecycleService lifecycleService;
    @Mock private TransactionTemplate transactionTemplate;
    @Spy private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private CampaignService campaignService;

    private Recipient alice, bob, carol, unsubscribedDan;

    @BeforeEach
    void setUp() {
        alice = Recipient.builder()
                .id(1L).name("Alice").email("alice@example.com")
                .tags(List.of("premium")).preferredLanguage("en")
                .status(RecipientStatus.ACTIVE)
                .build();

        bob = Recipient.builder()
                .id(2L).name("Bob").email("bob@example.com")
                .tags(List.of("bangalore")).preferredLanguage("en")
                .status(RecipientStatus.ACTIVE)
                .build();

        carol = Recipient.builder()
                .id(3L).name("Carol").email("carol@example.com")
                .tags(List.of("premium", "bangalore")).preferredLanguage("hi")
                .status(RecipientStatus.ACTIVE)
                .build();

        unsubscribedDan = Recipient.builder()
                .id(4L).name("Dan").email("dan@example.com")
                .tags(List.of("premium")).preferredLanguage("en")
                .status(RecipientStatus.UNSUBSCRIBED)
                .build();

        ReflectionTestUtils.setField(campaignService, "campaignDispatchExchangeName", "campaign.dispatch.exchange");
        ReflectionTestUtils.setField(campaignService, "campaignDispatchRoutingKey", "campaign.dispatch.route");
    }

    // Helper to call the private resolveRecipients() method via reflection,
    // since it's intentionally not part of the public API.
    @SuppressWarnings("unchecked")
    private List<Recipient> invokeResolveRecipients(Campaign campaign) throws Exception {
        Method method = CampaignService.class.getDeclaredMethod("resolveRecipients", Campaign.class);
        method.setAccessible(true);
        return (List<Recipient>) method.invoke(campaignService, campaign);
    }

    @Test
    void resolveRecipients_withExplicitIds_returnsOnlyThoseRecipients() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of(1L, 2L))
                .build();

        when(recipientRepository.findById(1L)).thenReturn(java.util.Optional.of(alice));
        when(recipientRepository.findById(2L)).thenReturn(java.util.Optional.of(bob));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void resolveRecipients_withTagFilter_matchesAnyTag_OR_logic() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .audienceTags(List.of("premium", "bangalore"))
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob, carol);
    }

    @Test
    void resolveRecipients_withTagAndLanguageFilter_appliesAND_logic() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .audienceTags(List.of("premium"))
                .audienceLanguage("en")
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        // Carol has "premium" tag but is "hi" language, so she should be excluded
        assertThat(result).containsExactly(alice);
    }

    @Test
    void resolveRecipients_alwaysExcludesUnsubscribed_evenWithExplicitIds() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of(4L))
                .build();

        when(recipientRepository.findById(4L)).thenReturn(java.util.Optional.of(unsubscribedDan));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).isEmpty();
    }

    @Test
    void resolveRecipients_withNoAudienceTagsOrLanguage_returnsAllNonUnsubscribed() throws Exception {
        Campaign campaign = Campaign.builder()
                .recipientIds(List.of())
                .build();

        when(recipientRepository.findByStatusNot(RecipientStatus.UNSUBSCRIBED))
                .thenReturn(List.of(alice, bob, carol));

        List<Recipient> result = invokeResolveRecipients(campaign);

        assertThat(result).containsExactlyInAnyOrder(alice, bob, carol);
    }

    @Test
    void resolveRecipients_skipsExplicitRecipientsThatNoLongerExist() throws Exception {
        Campaign campaign = Campaign.builder()
                .id(10L)
                .recipientIds(List.of(1L, 99L))
                .build();

        when(recipientRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(recipientRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(invokeResolveRecipients(campaign)).containsExactly(alice);
    }

    // ---------- create / update ----------

    @Test
    void create_persistsAudienceLanguageAndCreator() {
        when(templateRepository.findById(5L)).thenReturn(Optional.of(new Template()));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        campaignService.create(request(null), "admin");

        ArgumentCaptor<Campaign> saved = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(saved.capture());
        assertThat(saved.getValue().getAudienceLanguage()).isEqualTo("en");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
        assertThat(saved.getValue().getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void create_withFutureScheduleIsScheduled_andPastScheduleIsRejected() {
        when(templateRepository.findById(5L)).thenReturn(Optional.of(new Template()));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(campaignService.create(request(LocalDateTime.now().plusHours(1)), "admin").getStatus())
                .isEqualTo(CampaignStatus.SCHEDULED);
        assertThatThrownBy(() -> campaignService.create(request(LocalDateTime.now().minusMinutes(1)), "admin"))
                .isInstanceOf(InvalidScheduleTimeException.class);
    }

    @Test
    void update_isRejectedOnceTheCampaignIsRunning() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, CampaignStatus.RUNNING)));

        assertThatThrownBy(() -> campaignService.update(1L, request(null)))
                .isInstanceOf(CampaignNotEditableException.class);
        verify(campaignRepository, never()).save(any());
    }

    // ---------- send ----------

    @Test
    void send_claimsCampaignAndWritesDispatchMessageToOutbox() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, CampaignStatus.DRAFT)));
        when(campaignRepository.transitionStatus(eq(1L), eq(List.of(CampaignStatus.DRAFT)),
                eq(CampaignStatus.RUNNING), any())).thenReturn(1);

        campaignService.send(1L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq(OutboxEvent.AGGREGATE_CAMPAIGN), eq(1L), eq("campaign.dispatch.exchange"),
                eq("campaign.dispatch.route"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new CampaignDispatchMessage(1L));
    }

    @Test
    void send_rejectsCompletedCampaign() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, CampaignStatus.COMPLETED)));

        assertThatThrownBy(() -> campaignService.send(1L)).isInstanceOf(CampaignAlreadySentException.class);
        verify(outboxService, never()).enqueue(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void send_losingAConcurrentClaimDoesNotDispatchTwice() {
        when(campaignRepository.findById(1L)).thenReturn(
                Optional.of(campaign(1L, CampaignStatus.SCHEDULED)),
                Optional.of(campaign(1L, CampaignStatus.RUNNING)));
        when(campaignRepository.transitionStatus(eq(1L), eq(List.of(CampaignStatus.SCHEDULED)),
                eq(CampaignStatus.RUNNING), any())).thenReturn(0);

        assertThatThrownBy(() -> campaignService.send(1L))
                .isInstanceOf(CampaignAlreadySentException.class)
                .hasMessageContaining("RUNNING");
        verify(outboxService, never()).enqueue(anyString(), any(), anyString(), anyString(), any());
    }

    // ---------- dispatch fan-out ----------

    @Test
    void processDispatch_ignoresDuplicateMessageForCampaignThatIsNotRunning() {
        runTransactionCallbacksDirectly();
        when(campaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign(1L, CampaignStatus.COMPLETED)));

        campaignService.processDispatch(1L);

        verify(notificationRepository, never()).save(any());
        verify(lifecycleService, never()).enqueueDelivery(any());
        verify(campaignRepository, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void processDispatch_queuesReachableRecipientsAndFailsThoseWithoutAddress() {
        runTransactionCallbacksDirectly();
        Campaign smsCampaign = campaign(1L, CampaignStatus.RUNNING);
        smsCampaign.setChannel(NotificationChannel.SMS);
        smsCampaign.setRecipientIds(List.of(1L, 2L));
        smsCampaign.setCreatedBy("admin");
        alice.setPhoneNumber("+15550000001");   // bob has no phone number

        when(campaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(smsCampaign));
        when(templateRepository.findById(5L)).thenReturn(Optional.of(
                Template.builder().id(5L).subject("Hi {{name}}").body("Body for {{name}}").build()));
        when(recipientRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(recipientRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(templateService.render(any(), any())).thenReturn("rendered");
        List<Notification> saved = captureSavedNotifications();

        campaignService.processDispatch(1L);

        assertThat(saved).hasSize(2);
        Notification queued = saved.stream().filter(n -> n.getStatus() == NotificationStatus.QUEUED).findFirst().orElseThrow();
        assertThat(queued.getRecipientPhone()).isEqualTo("+15550000001");
        assertThat(queued.getCampaignId()).isEqualTo(1L);
        assertThat(queued.getOwner()).isEqualTo("admin");
        verify(lifecycleService, times(1)).enqueueDelivery(queued);
        assertThat(saved).anySatisfy(n -> {
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(n.getFailureReason()).contains("no phone number");
        });
        // COMPLETED is set on the locked entity and committed with the notifications
        assertThat(smsCampaign.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void processDispatch_truncatesRenderedSubjectsThatExceedTheColumn() {
        runTransactionCallbacksDirectly();
        Campaign emailCampaign = campaign(1L, CampaignStatus.RUNNING);
        emailCampaign.setRecipientIds(List.of(1L));
        when(campaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(emailCampaign));
        when(templateRepository.findById(5L)).thenReturn(Optional.of(
                Template.builder().id(5L).subject("{{long}}").body("b").build()));
        when(recipientRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(templateService.render(eq("{{long}}"), any())).thenReturn("x".repeat(400));
        when(templateService.render(eq("b"), any())).thenReturn("b");
        List<Notification> saved = captureSavedNotifications();

        campaignService.processDispatch(1L);

        assertThat(saved.get(0).getSubject()).hasSize(255).endsWith("...");
    }

    @Test
    void processDispatch_marksCampaignFailedWhenItsTemplateIsGone() {
        runTransactionCallbacksDirectly();
        when(campaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign(1L, CampaignStatus.RUNNING)));
        when(templateRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> campaignService.processDispatch(1L)).isInstanceOf(TemplateNotFoundException.class);
        verify(campaignRepository).transitionStatus(eq(1L), eq(List.of(CampaignStatus.RUNNING)),
                eq(CampaignStatus.FAILED), any());
    }

    // ---------- helpers ----------

    private CampaignRequest request(LocalDateTime scheduledTime) {
        return new CampaignRequest("Campaign", null, NotificationChannel.EMAIL, 5L, null, "en", null,
                Map.of(), scheduledTime);
    }

    private Campaign campaign(Long id, CampaignStatus status) {
        return Campaign.builder()
                .id(id).name("Campaign").channel(NotificationChannel.EMAIL).templateId(5L)
                .audienceLanguage("en").status(status)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacksDirectly() {
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private List<Notification> captureSavedNotifications() {
        List<Notification> saved = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(ids.incrementAndGet());
            saved.add(notification);
            return notification;
        });
        return saved;
    }

}
