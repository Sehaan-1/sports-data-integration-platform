package com.sdip.api;

import com.sdip.SdipApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sign-in page must load its local modules, icons, and fonts without a JWT. */
@SpringBootTest(classes = SdipApplication.class)
@AutoConfigureMockMvc
class FrontendAssetsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void frontendAndLocalAssetsArePublic() throws Exception {
        for (String asset : new String[]{
                "/index.html", "/dashboard.html", "/assets/styles.css", "/assets/app.js",
                "/assets/api.js", "/assets/model.js", "/assets/charts.js", "/assets/ui.js",
                "/assets/demo-data.js", "/assets/icons.svg", "/assets/favicon.svg",
                "/assets/fonts/manrope-latin.woff2", "/assets/fonts/jetbrains-mono-latin.woff2"
        }) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
    }

    @Test
    void publicAssetsDoNotMakeAthleteDataPublic() throws Exception {
        mvc.perform(get("/api/athletes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/team/load")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links/quarantine")).andExpect(status().isUnauthorized());
    }
}
