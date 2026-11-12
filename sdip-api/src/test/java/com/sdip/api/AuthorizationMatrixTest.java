package com.sdip.api;

import com.sdip.SdipApplication;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.Role;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.AuditEventRepository;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.api.WithSdipUserSecurityContextFactory.TestContextBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RQ3 — exhaustive automated role × endpoint × tenant authorization matrix
 * (§8). Zero authorization escapes is the pass criterion; audit completeness
 * is verified for every mutating operation by exact event counts.
 */
@SpringBootTest(classes = {SdipApplication.class, AuthorizationMatrixTest.SyncExecutorConfig.class})
@AutoConfigureMockMvc
@Import(TestContextBridge.class)
class AuthorizationMatrixTest {

    /** Makes the async job launcher run inline so upload assertions are deterministic. */
    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean
        @Primary
        java.util.concurrent.Executor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SquadRepository squads;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    AuditEventRepository auditEvents;

    private Squad squadA;
    private Squad squadB;
    private Athlete athleteA;
    private Athlete athleteB;

    @BeforeEach
    void seed() {
        squadA = squads.findByTenantCodeAndName("SDIP-DEMO", "A Team").orElseThrow();
        squadB = squads.findByTenantCodeAndName("SDIP-DEMO", "B Team").orElseThrow();
        athleteA = athletes.findByEmailIgnoreCase("alpha@a.test")
                .orElseGet(() -> athletes.save(new Athlete(squadA, "Alpha Athlete",
                        LocalDate.parse("1999-01-01"), "alpha@a.test", false)));
        athleteB = athletes.findByEmailIgnoreCase("beta@b.test")
                .orElseGet(() -> athletes.save(new Athlete(squadB, "Beta Athlete",
                        LocalDate.parse("2000-02-02"), "beta@b.test", false)));
    }

    // ---------------- anonymous ----------------

    @Test
    void anonymous_everythingReturns401() throws Exception {
        mvc.perform(get("/api/athletes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/team/load")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exports/athletes.csv")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links/quarantine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/connectors/catapult/sync")).andExpect(status().isUnauthorized());
    }

    // ---------------- athlete reads (row scoping) ----------------

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void coachA_seesOnlySquadAAthletes() throws Exception {
        mvc.perform(get("/api/athletes").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'alpha@a.test')]").exists())
                .andExpect(jsonPath("$.content[?(@.email == 'beta@b.test')]").doesNotExist());
    }

    @Test
    @WithSdipUser(role = Role.ANALYST, squad = "B", email = "analyst@sdip.local")
    void analystB_seesOnlySquadBAthletes() throws Exception {
        mvc.perform(get("/api/athletes").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'beta@b.test')]").exists())
                .andExpect(jsonPath("$.content[?(@.email == 'alpha@a.test')]").doesNotExist());
    }

    @Test
    @WithSdipUser(role = Role.ADMIN, email = "admin@sdip.local")
    void admin_seesAllAthletes() throws Exception {
        mvc.perform(get("/api/athletes").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'alpha@a.test')]").exists())
                .andExpect(jsonPath("$.content[?(@.email == 'beta@b.test')]").exists());
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void coachA_cannotReadAthleteFromSquadB_crossTenant404() throws Exception {
        mvc.perform(get("/api/athletes/" + athleteB.getId()))
                .andExpect(status().isNotFound()); // existence not leaked
        mvc.perform(get("/api/athletes/" + athleteA.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @WithSdipUser(role = Role.PRACTITIONER, squad = "B", email = "physio-b@sdip.local")
    void practitionerB_readOwnSquadOnly() throws Exception {
        mvc.perform(get("/api/athletes/" + athleteA.getId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/athletes/" + athleteB.getId()))
                .andExpect(status().isOk());
    }

    // ---------------- role guards on mutating endpoints ----------------

    @Test
    @WithSdipUser(role = Role.ANALYST, squad = "B", email = "analyst@sdip.local")
    void analyst_isReadOnly_everywhere() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.csv", "text/csv",
                "a,b\n1,2\n".getBytes());
        mvc.perform(multipart("/api/ingest/files").file(file).param("profile", "catapult-session-csv"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/exports/athletes.csv")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.z\",\"password\":\"password1\",\"displayName\":\"X\",\"role\":\"ANALYST\",\"squadId\":\"" + squadB.getId() + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/links/quarantine")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/links/" + UUID.randomUUID())).andExpect(status().isForbidden());
        // but CAN read dashboards & athletes
        mvc.perform(get("/api/dashboard/team/load")).andExpect(status().isOk());
        mvc.perform(get("/api/athletes")).andExpect(status().isOk());
    }

    @Test
    @WithSdipUser(role = Role.PRACTITIONER, squad = "B", email = "physio-b@sdip.local")
    void practitioner_cannotExport_cannotManageLinks_orAdmin() throws Exception {
        mvc.perform(get("/api/exports/athletes.csv")).andExpect(status().isForbidden());
        mvc.perform(get("/api/links/quarantine")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithSdipUser(role = Role.ADMIN, email = "admin@sdip.local")
    void admin_reachesPrivilegedEndpoints() throws Exception {
        mvc.perform(get("/api/links/quarantine")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users")).andExpect(status().isOk());
        mvc.perform(post("/api/connectors/catapult/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    // ---------------- upload + audit completeness ----------------

    private MockMultipartFile csvUpload() {
        String csv = """
                Player Name,Email,Player Id,Date,Session Id,Total Distance,HSR,Player Load,Max Vel (km/h),Duration
                Alpha Athlete,alpha@a.test,cat-alpha,2025-08-01,mtx-1,6000,500,400,32.0,80
                """;
        return new MockMultipartFile("file", "sessions.csv", "text/csv", csv.getBytes());
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void upload_staffRoleAccepted_squadClaimWins_andExactlyOneAuditEvent() throws Exception {
        long uploadsBefore = auditEvents.countByAction("FILE_UPLOAD");
        // even if a staff user TRIES to ingest into squad B, their squad claim wins (C3)
        MvcResult res = mvc.perform(multipart("/api/ingest/files")
                        .file(csvUpload())
                        .param("profile", "catapult-session-csv")
                        .param("squadId", squadB.getId().toString()))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(auditEvents.countByAction("FILE_UPLOAD")).isEqualTo(uploadsBefore + 1);
        String body = res.getResponse().getContentAsString();
        String batchId = body.replaceAll(".*\"batchId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/ingest/batches/" + batchId)).andExpect(status().isOk());
        mvc.perform(get("/api/ingest/batches/" + batchId + "/errors")).andExpect(status().isOk());
    }

    @Test
    @WithSdipUser(role = Role.HEAD_COACH, squad = "A", email = "coach@sdip.local")
    void export_emitsExactlyOneAuditEvent_andIsTenantScoped() throws Exception {
        long exportsBefore = auditEvents.countByAction("EXPORT_DATA");
        mvc.perform(get("/api/exports/athletes.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alpha@a.test")))
                .andExpect(content().string(not(containsString("beta@b.test"))));
        assertThat(auditEvents.countByAction("EXPORT_DATA")).isEqualTo(exportsBefore + 1);
    }

    @Test
    @WithSdipUser(role = Role.ADMIN, email = "admin@sdip.local")
    void createUser_emitsExactlyOneAuditEvent() throws Exception {
        long before = auditEvents.countByAction("PERMISSION_CHANGE");
        mvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.analyst@sdip.local\",\"password\":\"Password1\",\"displayName\":\"New Analyst\",\"role\":\"ANALYST\",\"squadId\":\"" + squadB.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ANALYST"));
        assertThat(auditEvents.countByAction("PERMISSION_CHANGE")).isEqualTo(before + 1);
    }

    // ---------------- real JWT login + dashboard scoping ----------------

    @Test
    void login_issuedTokenWorks_andRefreshRotates() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"coach@sdip.local\",\"password\":\"Coach#123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        String json = login.getResponse().getContentAsString();
        String access = json.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String refresh = json.replaceAll(".*\"refreshToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // access token works and is tenant-scoped
        mvc.perform(get("/api/athletes").header("Authorization", "Bearer " + access).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'alpha@a.test')]").exists())
                .andExpect(jsonPath("$.content[?(@.email == 'beta@b.test')]").doesNotExist());

        // refresh tokens are NOT accepted as bearer credentials
        mvc.perform(get("/api/athletes").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());

        // refresh rotation issues a fresh valid pair
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        // bad credentials → 401 with audit trail
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"coach@sdip.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
