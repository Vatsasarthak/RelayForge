package com.vatsasarthak.notifyr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vatsasarthak.notifyr.entity.Notification;
import com.vatsasarthak.notifyr.entity.NotificationChannel;
import com.vatsasarthak.notifyr.repository.NotificationRepository;
import com.vatsasarthak.notifyr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End-to-end tests through the HTTP layer (security filters, validation, error handling) against real
// PostgreSQL, RabbitMQ and Redis containers. Email/SMS use the mock providers (notification.mock-provider=true).
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

    private static final String API_KEY = "test-api-key";
    private static final String ADMIN_USERNAME = "it-admin";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;

    // ---------- monitoring and docs ----------

    @Test
    void actuatorHealthIsPublicAndHidesDetailsFromAnonymousCallers() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void actuatorHealthShowsComponentDetailsToAdmins() throws Exception {
        mvc.perform(get("/actuator/health").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.rabbit.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"))
                .andExpect(jsonPath("$.components.outbox.status").value("UP"))
                .andExpect(jsonPath("$.components.outbox.details.pendingEvents").exists());
    }

    @Test
    void prometheusMetricsAreExposed() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("outbox_events_pending")));
    }

    @Test
    void openApiDocsAndSwaggerUiAreServedWithoutApiKey() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-KEY"))
                .andExpect(jsonPath("$.paths['/notifications/send']").exists());
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void correlationIdIsEchoedAndIncludedInErrors() throws Exception {
        mvc.perform(get("/notifications/1").header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-abc-123"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/notifications/1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------- authentication ----------

    @Test
    void requestsWithoutValidApiKeyAreRejected() throws Exception {
        mvc.perform(get("/notifications/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("API key")));
        mvc.perform(get("/notifications/1").header("X-API-KEY", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerHashesPasswordAndLoginValidatesCredentials() throws Exception {
        String username = "client-" + UUID.randomUUID();

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", PASSWORD, "role", "API_CLIENT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("API_CLIENT"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        String storedPassword = userRepository.findByUsername(username).orElseThrow().getPassword();
        assertThat(storedPassword).isNotEqualTo(PASSWORD).startsWith("$2");

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "nobody-" + UUID.randomUUID(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", PASSWORD, "role", "API_CLIENT"))))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedAndInvalidRequestsReturn400() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.username").exists())
                .andExpect(jsonPath("$.details.password").exists())
                .andExpect(jsonPath("$.details.role").exists());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "someone", "password", PASSWORD, "role", "SUPERUSER"))))
                .andExpect(status().isBadRequest());

        String client = registerClient();
        mvc.perform(asUser(get("/notifications/not-a-number"), client))
                .andExpect(status().isBadRequest());
        mvc.perform(asUser(get("/notifications?sort=noSuchField"), client))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("noSuchField")));
        mvc.perform(asUser(post("/notifications/send"), client)
                        .header("Idempotency-Key", "k".repeat(300))
                        .content(body(Map.of("recipientEmail", "a@example.com", "subject", "s", "message", "m"))))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin(post("/recipients"))
                        .content(body(Map.of("name", "Bad phone", "email", UUID.randomUUID() + "@example.com",
                                "phoneNumber", "12345"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.phoneNumber").exists());
    }

    // regression: Spring MVC's own client errors fell through to the catch-all handler and were reported as 500
    @Test
    void unsupportedContentTypeIsAClientErrorNotAServerError() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.TEXT_PLAIN).content("username=a&password=b"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    // the public /auth endpoints must not buffer arbitrarily large bodies
    @Test
    void oversizedRequestBodiesAreRejected() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "someone", "password", "x".repeat(2 * 1024 * 1024)))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void onlyAnExistingAdminCanRegisterAnotherAdmin() throws Exception {
        String adminToken = adminToken();
        String clientToken = registerClient();

        Map<String, String> newAdmin = Map.of("username", "admin-" + UUID.randomUUID(), "password", PASSWORD,
                "role", "ADMIN");

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(newAdmin)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(newAdmin))
                        .header("Authorization", "Bearer " + clientToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(newAdmin))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminEndpointsReturn401WithoutTokenAnd403ForNonAdmins() throws Exception {
        Map<String, String> template = templateBody("tpl-" + UUID.randomUUID());
        String client = registerClient();

        mvc.perform(withKey(post("/templates")).content(body(template)))
                .andExpect(status().isUnauthorized());
        mvc.perform(withKey(post("/templates")).content(body(template))
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(asUser(post("/templates"), client).content(body(template)))
                .andExpect(status().isForbidden());
        mvc.perform(asAdmin(post("/templates")).content(body(template)))
                .andExpect(status().isCreated());
        // duplicate template name -> 409
        mvc.perform(asAdmin(post("/templates")).content(body(template)))
                .andExpect(status().isConflict());
        // read endpoints only need the API key
        mvc.perform(withKey(get("/templates")))
                .andExpect(status().isOk());
        // operational endpoints are ADMIN only
        mvc.perform(asUser(get("/admin/dead-letters"), client))
                .andExpect(status().isForbidden());
        mvc.perform(asAdmin(get("/admin/dead-letters")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mvc.perform(asUser(post("/notifications/1/retry"), client))
                .andExpect(status().isForbidden());
    }

    // ---------- notifications ----------

    @Test
    void notificationsRequireAUserToken() throws Exception {
        mvc.perform(withKey(post("/notifications/send"))
                        .content(body(Map.of("recipientEmail", "a@example.com", "subject", "s", "message", "m"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(withKey(get("/notifications")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationIsQueuedProcessedAsSimulatedAndIdempotent() throws Exception {
        String client = registerClient();
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, String> request = Map.of("recipientEmail", "it-user@example.com",
                "subject", "Hello", "message", "Integration test message");

        JsonNode first = readJson(mvc.perform(asUser(post("/notifications/send"), client).content(body(request))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/notifications/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.channel").value("EMAIL")));
        long id = first.get("id").asLong();

        // same key + same body -> same notification, nothing new is created
        mvc.perform(asUser(post("/notifications/send"), client).content(body(request))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id));

        // same key + different body -> 409
        mvc.perform(asUser(post("/notifications/send"), client)
                        .content(body(Map.of("recipientEmail", "it-user@example.com", "subject", "Other",
                                "message", "Different body")))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isConflict());

        // the mock email provider never reports a simulated message as SENT
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(asUser(get("/notifications/" + id), client))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("SIMULATED"))
                        .andExpect(jsonPath("$.sentAt").exists()));

        mvc.perform(asUser(get("/notifications/999999999"), client))
                .andExpect(status().isNotFound());

        mvc.perform(asUser(post("/notifications/send"), client)
                        .content(body(Map.of("recipientEmail", "not-an-email", "subject", "s", "message", "m"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.recipientEmail").exists());
    }

    @Test
    void usersCannotSeeEachOthersNotifications() throws Exception {
        String alice = registerClient();
        String bob = registerClient();

        long alicesId = readJson(mvc.perform(asUser(post("/notifications/send"), alice)
                        .content(body(Map.of("recipientEmail", "alice@example.com", "subject", "Private",
                                "message", "for alice only"))))
                .andExpect(status().isAccepted())).get("id").asLong();

        // bob gets 404, not 403, so he cannot even learn that the id exists
        mvc.perform(asUser(get("/notifications/" + alicesId), bob))
                .andExpect(status().isNotFound());
        mvc.perform(asUser(get("/notifications"), bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(asUser(get("/notifications/" + alicesId), alice))
                .andExpect(status().isOk());
        mvc.perform(asAdmin(get("/notifications/" + alicesId)))
                .andExpect(status().isOk());
    }

    @Test
    void notificationListIsPaginatedNewestFirst() throws Exception {
        String client = registerClient();
        for (int i = 0; i < 3; i++) {
            mvc.perform(asUser(post("/notifications/send"), client)
                            .content(body(Map.of("recipientEmail", "page@example.com", "subject", "n" + i,
                                    "message", "m"))))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(asUser(get("/notifications?size=2"), client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].subject").value("n2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0));
        mvc.perform(asUser(get("/notifications?size=2&page=1"), client))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].subject").value("n0"));
        // page size is capped
        mvc.perform(asUser(get("/notifications?size=5000"), client))
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---------- campaigns ----------

    @Test
    void campaignFansOutPerRecipientAndCannotBeSentTwice() throws Exception {
        String tag = "it-sms-" + UUID.randomUUID();
        long templateId = createTemplate();
        createRecipient("with-phone", "+15550000001", List.of(tag));
        createRecipient("without-phone", null, List.of(tag));
        createRecipient("other-tag", "+15550000002", List.of("unrelated-" + UUID.randomUUID()));

        long campaignId = readJson(mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "SMS campaign", "channel", "SMS", "templateId", templateId,
                        "audienceTags", List.of(tag), "templateVariables", Map.of("code", "SAVE10")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))).get("id").asLong();

        mvc.perform(asAdmin(post("/campaigns/" + campaignId + "/send")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", anyOf(is("RUNNING"), is("COMPLETED"))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(withKey(get("/campaigns/" + campaignId)))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));

        // already sent -> 409, and no second fan-out
        mvc.perform(asAdmin(post("/campaigns/" + campaignId + "/send")))
                .andExpect(status().isConflict());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Notification> sms = notificationRepository.findAll().stream()
                    .filter(n -> n.getChannel() == NotificationChannel.SMS)
                    .filter(n -> campaignId == (n.getCampaignId() == null ? -1 : n.getCampaignId()))
                    .toList();
            assertThat(sms).hasSize(2);
            assertThat(sms).anySatisfy(n -> {
                assertThat(n.getRecipientPhone()).isEqualTo("+15550000001");
                assertThat(n.getStatus().name()).isEqualTo("SIMULATED");
                assertThat(n.getMessage()).contains("Hi with-phone", "SAVE10");
            });
            assertThat(sms).anySatisfy(n -> {
                assertThat(n.getRecipientPhone()).isNull();
                assertThat(n.getStatus().name()).isEqualTo("FAILED");
                assertThat(n.getFailureReason()).contains("no phone number");
            });
        });

        mvc.perform(withKey(get("/campaigns/" + campaignId + "/stats")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalNotifications").value(2))
                .andExpect(jsonPath("$.notificationsByStatus.SIMULATED").value(1))
                .andExpect(jsonPath("$.notificationsByStatus.FAILED").value(1));
    }

    @Test
    void campaignCreationValidatesInput() throws Exception {
        long templateId = createTemplate();

        // language-only targeting is stored (it used to be dropped, which targeted every recipient)
        mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "Language campaign", "channel", "EMAIL", "templateId", templateId,
                        "audienceLanguage", "fr"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audienceLanguage").value("fr"));

        mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "Past", "channel", "EMAIL", "templateId", templateId, "audienceLanguage", "en",
                        "scheduledTime", LocalDateTime.now().minusMinutes(5).withNano(0).toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("scheduledTime must be in the future"));

        mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "No target", "channel", "EMAIL", "templateId", templateId))))
                .andExpect(status().isBadRequest());

        mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "channel", "EMAIL", "templateId", templateId, "audienceLanguage", "en"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists());

        mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "Missing template", "channel", "EMAIL", "templateId", 999999999,
                        "audienceLanguage", "en"))))
                .andExpect(status().isNotFound());

        mvc.perform(withKey(get("/campaigns/999999999")))
                .andExpect(status().isNotFound());

        mvc.perform(withKey(get("/campaigns?size=1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void scheduledCampaignIsDispatchedByTheScheduler() throws Exception {
        String tag = "it-scheduled-" + UUID.randomUUID();
        long templateId = createTemplate();
        createRecipient("scheduled", null, List.of(tag));

        long campaignId = readJson(mvc.perform(asAdmin(post("/campaigns")).content(body(Map.of(
                        "name", "Scheduled", "channel", "EMAIL", "templateId", templateId,
                        "audienceTags", List.of(tag),
                        "scheduledTime", LocalDateTime.now().plusSeconds(2).withNano(0).toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))).get("id").asLong();

        // the scheduler polls every 30 seconds
        await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                mvc.perform(withKey(get("/campaigns/" + campaignId)))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
    }

    // ---------- helpers ----------

    // the ADMIN is created at startup by AdminBootstrap (notification.bootstrap-admin.* in application-test.yaml)
    private String adminToken() throws Exception {
        return readJson(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", ADMIN_USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())).get("token").asText();
    }

    private String registerClient() throws Exception {
        return readJson(mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "client-" + UUID.randomUUID(), "password", PASSWORD,
                                "role", "API_CLIENT"))))
                .andExpect(status().isCreated())).get("token").asText();
    }

    private long createTemplate() throws Exception {
        return readJson(mvc.perform(asAdmin(post("/templates"))
                        .content(body(templateBody("tpl-" + UUID.randomUUID()))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, String> templateBody(String name) {
        return Map.of("name", name, "subject", "Offer for {{name}}",
                "body", "Hi {{name}}, use code {{code}}. " + name);
    }

    private long createRecipient(String name, String phone, List<String> tags) throws Exception {
        Map<String, Object> recipient = new java.util.HashMap<>(Map.of(
                "name", name, "email", name + "-" + UUID.randomUUID() + "@example.com", "tags", tags));
        if (phone != null) {
            recipient.put("phoneNumber", phone);
        }
        return readJson(mvc.perform(asAdmin(post("/recipients")).content(body(recipient)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header("X-API-KEY", API_KEY).contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String token) {
        return withKey(request).header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return asUser(request, adminToken());
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
