package com.sdip.connectors;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sdip.TestConnectorsApp;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.AthleteSourceLinkRepository;
import com.sdip.domain.repo.ConnectorStateRepository;
import com.sdip.domain.repo.ForceTestRepository;
import com.sdip.domain.repo.GpsMetricRepository;
import com.sdip.domain.repo.LinkQuarantineRepository;
import com.sdip.domain.repo.SquadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RQ4 — connector resilience via WireMock fault injection against
 * contract-faithful replica endpoints (§4.1, §8): happy path + idempotent
 * re-sync + late-edit propagation, 429 storms, malformed JSON, and the VALD
 * OAuth2 flow. Also proves C4: zero duplicates on replays; watermark only
 * advances on full success.
 */
@SpringBootTest(classes = TestConnectorsApp.class)
class ConnectorResilienceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void connectorProps(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + wm.getPort();
        registry.add("sdip.connectors.vendors.catapult.enabled", () -> "true");
        registry.add("sdip.connectors.vendors.catapult.base-url", () -> base);
        registry.add("sdip.connectors.vendors.catapult.auth-type", () -> "TOKEN");
        registry.add("sdip.connectors.vendors.catapult.token", () -> "test-token");
        registry.add("sdip.connectors.vendors.catapult.overlap-hours", () -> "48");
        registry.add("sdip.connectors.vendors.vald.enabled", () -> "true");
        registry.add("sdip.connectors.vendors.vald.base-url", () -> base);
        registry.add("sdip.connectors.vendors.vald.auth-type", () -> "OAUTH2");
        registry.add("sdip.connectors.vendors.vald.token-uri", () -> "/vald/oauth/token");
        registry.add("sdip.connectors.vendors.vald.client-id", () -> "sdip");
        registry.add("sdip.connectors.vendors.vald.client-secret", () -> "topsecret");
        registry.add("sdip.connectors.vendors.vald.overlap-hours", () -> "48");
    }

    @Autowired
    SyncOrchestrator orchestrator;
    @Autowired
    ConnectorProperties properties;
    @Autowired
    ResilienceSupport resilience;
    @Autowired
    SquadRepository squads;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    GpsMetricRepository gps;
    @Autowired
    ForceTestRepository force;
    @Autowired
    AthleteSourceLinkRepository links;
    @Autowired
    LinkQuarantineRepository quarantine;
    @Autowired
    ConnectorStateRepository states;
    @Autowired
    JdbcTemplate jdbc;

    private Squad squad;

    @BeforeEach
    void setup() {
        gps.deleteAll();
        force.deleteAll();
        links.deleteAll();
        quarantine.deleteAll();
        athletes.deleteAll();
        states.deleteAll();
        wm.resetAll();
        resilience.reset("catapult");
        resilience.reset("vald");
        squad = squads.findByTenantCodeAndName("CONN", "Connector Squad")
                .orElseGet(() -> squads.save(new Squad("Connector Squad", "CONN")));
        properties.require("catapult").setSquadId(squad.getId());
        properties.require("vald").setSquadId(squad.getId());
    }

    // ---------- stubs ----------

    private void stubCatapultAthletes() {
        wm.stubFor(get(urlPathEqualTo("/openfield/api/v1/athletes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"athletes": [
                          {"id": "cat-1", "first_name": "Grace", "last_name": "Wallace",
                           "email": "grace.wallace@conn.test", "date_of_birth": "1997-04-03"},
                          {"id": "cat-2", "first_name": "Ivy", "last_name": "Zhang",
                           "email": null, "date_of_birth": "1998-05-05"}
                        ]}
                        """)));
    }

    private void stubCatapultActivities(String body) {
        wm.stubFor(get(urlPathEqualTo("/openfield/api/v1/activities")).atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String activitiesJson(double a1Total) {
        return """
                {"activities": [
                  {"id": "act-a1", "athlete_id": "cat-1", "start_time": "2025-08-10T09:30:00Z",
                   "duration_minutes": 82.5, "total_distance": %s, "high_speed_running": 610.0,
                   "player_load": 421.0, "max_velocity": 33.1},
                  {"id": "act-a2", "athlete_id": "cat-1", "start_time": "2025-08-12T09:30:00Z",
                   "duration_minutes": 75.0, "total_distance": 5300.0, "high_speed_running": 402.0,
                   "player_load": 377.0, "max_velocity": 31.4},
                  {"id": "act-a3", "athlete_id": "cat-2", "start_time": "2025-08-11T09:30:00Z",
                   "duration_minutes": 80.0, "total_distance": 6100.0, "high_speed_running": 501.0,
                   "player_load": 402.0, "max_velocity": 32.2}
                ], "page": {"number": 0, "total_pages": 1}}
                """.formatted(a1Total);
    }

    // ---------- scenarios ----------

    @Test
    void happyPath_thenIdempotentReSync_thenLateEditPropagates() {
        athletes.save(new Athlete(squad, "Grace Wallace", LocalDate.parse("1997-04-03"),
                "grace.wallace@conn.test", false));

        stubCatapultAthletes();
        stubCatapultActivities(activitiesJson(6500.0));

        SyncReport first = orchestrator.sync("catapult");
        assertThat(first.status()).as("first sync degraded: %s", first.error()).isEqualTo(SyncReport.Status.OK);
        assertThat(first.recordsRead()).isEqualTo(3);
        assertThat(first.recordsWritten()).isEqualTo(3);
        assertThat(gps.count()).isEqualTo(3);
        Instant watermark1 = states.findById("catapult").orElseThrow().getLastSyncedAt();
        assertThat(watermark1).isNotNull();

        // C4: re-running the identical sync creates ZERO duplicates
        SyncReport second = orchestrator.sync("catapult");
        assertThat(second.status()).as("second sync degraded: %s", second.error()).isEqualTo(SyncReport.Status.OK);
        assertThat(gps.count()).isEqualTo(3);

        // Vendor edits a delivered activity inside the overlap window → propagated by upsert
        stubCatapultActivities(activitiesJson(9999.0));
        SyncReport third = orchestrator.sync("catapult");
        assertThat(third.status()).as("third sync degraded: %s", third.error()).isEqualTo(SyncReport.Status.OK);
        assertThat(gps.count()).isEqualTo(3);
        Double updated = jdbc.queryForObject(
                "SELECT total_distance_m FROM gps_metric WHERE source_system='CATAPULT' AND source_record_id='act-a1'",
                Double.class);
        assertThat(updated).isEqualTo(9999.0);
        var lastSync = jdbc.queryForObject(
                "SELECT last_vendor_sync FROM gps_metric WHERE source_system='CATAPULT' AND source_record_id='act-a1'",
                java.sql.Timestamp.class);
        assertThat(lastSync).isNotNull();

        // previously unknown athlete (cat-2) was provisioned and stably linked
        assertThat(links.findBySourceSystemAndSourceAthleteIdAndStatus("CATAPULT", "cat-2",
                com.sdip.domain.entity.AthleteSourceLink.LinkStatus.ACTIVE)).isPresent();
        Instant watermark3 = states.findById("catapult").orElseThrow().getLastSyncedAt();
        assertThat(watermark3).isAfter(watermark1);
    }

    @Test
    void rateLimitStorm429_degradesCleanly_noPartialWrites_watermarkUntouched() {
        stubCatapultAthletes();
        wm.stubFor(get(urlPathEqualTo("/openfield/api/v1/activities"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")
                        .withBody("{\"error\":\"rate limited\"}")));

        SyncReport report = orchestrator.sync("catapult");

        assertThat(report.status()).isEqualTo(SyncReport.Status.DEGRADED);
        assertThat(report.error()).contains("429");
        assertThat(gps.count()).isZero(); // no partial writes
        // watermark row may be absent or present-but-null; either way it never advanced
        assertThat(states.findById("catapult").map(com.sdip.domain.entity.ConnectorState::getLastSyncedAt))
                .isEmpty();
        wm.verify(4, getRequestedFor(urlPathEqualTo("/openfield/api/v1/activities"))); // bounded retries (4 attempts)
    }

    @Test
    void malformedJsonPayload_degradesWithoutWriting() {
        stubCatapultAthletes();
        wm.stubFor(get(urlPathEqualTo("/openfield/api/v1/activities"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("<html>this is not json</html>")));

        SyncReport report = orchestrator.sync("catapult");
        assertThat(report.status()).isEqualTo(SyncReport.Status.DEGRADED);
        assertThat(gps.count()).isZero();
        assertThat(states.findById("catapult").map(com.sdip.domain.entity.ConnectorState::getLastSyncedAt))
                .isEmpty();
    }

    @Test
    void valdOAuth2Flow_syncsForceTests_andIsIdempotent() {
        athletes.save(new Athlete(squad, "Lena Petrova", LocalDate.parse("1996-02-14"),
                "lena.petrova@conn.test", false));

        wm.stubFor(post(urlPathEqualTo("/vald/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"vald-token-xyz\",\"expires_in\":3600}")));
        wm.stubFor(get(urlPathEqualTo("/vald/api/v1/tests"))
                .withHeader("Authorization", matching("Bearer vald-token-xyz"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"tests": [
                          {"test_id": "t-1001", "profile_id": "p-1", "athlete_name": "Lena Petrova",
                           "email": "lena.petrova@conn.test", "test_type": "CMJ",
                           "recorded_utc": "2025-08-12T10:00:00Z", "peak_force_n": 1820.0,
                           "asymmetry_pct": 4.2, "rsi_modified": 0.42, "peak_power_w": 4510.0},
                          {"test_id": "t-1002", "profile_id": "p-1", "athlete_name": "Lena Petrova",
                           "email": "lena.petrova@conn.test", "test_type": "NORDIC",
                           "recorded_utc": "2025-08-12T10:05:00Z", "peak_force_n": 410.0,
                           "asymmetry_pct": 9.8, "rsi_modified": null, "peak_power_w": null}
                        ], "page": {"number": 0, "total_pages": 1}}
                        """)));

        SyncReport first = orchestrator.sync("vald");
        assertThat(first.status()).as("first sync degraded: %s", first.error()).isEqualTo(SyncReport.Status.OK);
        assertThat(first.recordsWritten()).isEqualTo(2);
        assertThat(force.count()).isEqualTo(2);

        SyncReport second = orchestrator.sync("vald");
        assertThat(second.status()).as("second sync degraded: %s", second.error()).isEqualTo(SyncReport.Status.OK);
        assertThat(force.count()).isEqualTo(2); // zero duplicates on replay
        assertThat(states.findById("vald").orElseThrow().getLastSyncedAt()).isNotNull();

        wm.verify(2, postRequestedFor(urlPathEqualTo("/vald/oauth/token"))); // one token per sync (client-per-run)
        wm.verify(2, getRequestedFor(urlPathEqualTo("/vald/api/v1/tests"))); // one paged fetch per sync
    }

    @Test
    void circuitBreakerTripsAfterRepeatedVendorFailures() {
        wm.stubFor(get(urlPathEqualTo("/openfield/api/v1/athletes"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));

        // repeated runs pound the failing athletes call until the breaker opens
        for (int i = 0; i < 8 && resilience.breakerState("catapult")
                != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN; i++) {
            orchestrator.sync("catapult");
        }
        assertThat(resilience.breakerState("catapult"))
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        assertThat(gps.count()).isZero();
    }
}
