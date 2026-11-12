package com.sdip.api;

import com.sdip.SdipApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The compose healthcheck (and any operator) must be able to read liveness
 * without credentials, while every other actuator endpoint stays closed.
 */
@SpringBootTest(classes = SdipApplication.class)
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousHealthCheckReportsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessProbesExistForCompose() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsAreNotExposedAnonymously() throws Exception {
        // Either "not exposed" (404) or "not authenticated" (401) is acceptable.
        // What matters is that anonymous callers cannot read the environment.
        mvc.perform(get("/actuator/env"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 404));
    }
}
