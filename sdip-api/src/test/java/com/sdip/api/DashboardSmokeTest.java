package com.sdip.api;

import com.sdip.SdipApplication;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Role;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.api.WithSdipUserSecurityContextFactory.TestContextBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: the dashboard JDBC paths must work on the H2-PG-compat runtime
 * AND on PostgreSQL — reserved-word aliases broke the athlete load endpoints
 * once in production-simulation. Also exercises cross-squad 404 on dashboards.
 */
@SpringBootTest(classes = {SdipApplication.class, AuthorizationMatrixTest.SyncExecutorConfig.class})
@AutoConfigureMockMvc
@Import(TestContextBridge.class)
class DashboardSmokeTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SquadRepository squads;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    JdbcTemplate jdbc;

    private Squad squadA;
    private Athlete athlete;

    @BeforeEach
    void seed() {
        squadA = squads.findByTenantCodeAndName("SDIP-DEMO", "A Team").orElseThrow();
        athlete = athletes.findFirstByEmailIgnoreCaseAndSquad_Id("dash@a.test", squadA.getId())
                .orElseGet(() -> athletes.save(new Athlete(squadA, "Dash Athlete",
                        LocalDate.parse("1998-05-05"), "dash@a.test", false)));
        Instant t1 = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant t2 = Instant.now().minus(10, ChronoUnit.DAYS);
        insertGps("dash-sess-1", t1, 5000.0, 400.0);
        insertGps("dash-sess-2", t2, 4500.0, 350.0);
        if (countRows("force_test", "dash-ft-1") == 0) {
            jdbc.update("""
                    INSERT INTO force_test (id, athlete_id, squad_id, test_type, tested_at,
                                            peak_force_n, asymmetry_pct, rsi_modified,
                                            source_system, source_record_id)
                    VALUES (?, ?, ?, 'CMJ', ?, 1950, 4.5, 0.42, 'VALD', ?)
                    """, UUID.randomUUID(), athlete.getId(), squadA.getId(),
                    Timestamp.from(t2), "dash-ft-1");
        }
    }

    private int countRows(String table, String recordId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE source_system IN ('CATAPULT','VALD') AND source_record_id = ?", Integer.class, recordId);
        return n != null ? n : 0;
    }

    private void insertGps(String recordId, Instant at, double distance, double load) {
        if (countRows("gps_metric", recordId) > 0) {
            return; // idempotent re-seed across test methods sharing the context
        }
        jdbc.update("""
                INSERT INTO gps_metric (id, athlete_id, squad_id, recorded_at,
                                        total_distance_m, hsr_distance_m, player_load, max_velocity_kmh,
                                        source_system, source_record_id)
                VALUES (?, ?, ?, ?, ?, 500, ?, 32.0, 'CATAPULT', ?)
                """, UUID.randomUUID(), athlete.getId(), squadA.getId(),
                Timestamp.from(at), distance, load, recordId);
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void athleteLoad_returnsDailySeriesWithAcwr() throws Exception {
        mvc.perform(get("/api/dashboard/athlete/" + athlete.getId() + "/load").param("days", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].playerLoad").value(350.0))
                .andExpect(jsonPath("$.days[1].playerLoad").value(400.0));
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void fatigueOverlay_mergesForceTests() throws Exception {
        mvc.perform(get("/api/dashboard/athlete/" + athlete.getId() + "/fatigue-overlay")
                        .param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.athleteName").value("Dash Athlete"))
                .andExpect(jsonPath("$.forceTests.length()").value(1))
                .andExpect(jsonPath("$.forceTests[0].rsiModified").value(0.42))
                .andExpect(jsonPath("$.days.length()").value(2));
    }

    @Test
    @WithSdipUser(role = Role.ANALYST, squad = "B", email = "analyst@sdip.local")
    void crossSquad_dashboardIs404_andTeamLoadEmpty() throws Exception {
        mvc.perform(get("/api/dashboard/athlete/" + athlete.getId() + "/load"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard/team/load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0)); // squad B has no sessions
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void teamLoad_containsSeededAthlete_onceNotDuplicated() throws Exception {
        mvc.perform(get("/api/dashboard/team/load").param("days", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fullName == 'Dash Athlete')].sessions").value(2));
    }
}
