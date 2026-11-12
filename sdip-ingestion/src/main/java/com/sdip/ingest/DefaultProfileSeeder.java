package com.sdip.ingest;

import com.sdip.domain.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ships default column-mapping profiles for the two vendor spreadsheet
 * dialects used in development and the demo (§4.2). Profiles are rows, so
 * administrators can evolve them without redeploying.
 */
@Component
public class DefaultProfileSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultProfileSeeder.class);

    private final ColumnMappingService mappingService;
    private final boolean enabled;

    public DefaultProfileSeeder(ColumnMappingService mappingService,
                                @Value("${sdip.ingestion.seed-default-profiles:true}") boolean enabled) {
        this.mappingService = mappingService;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!mappingService.listProfiles().isEmpty()) {
            return;
        }
        mappingService.upsertProfile("catapult-session-csv", EntityType.GPS, "CATAPULT", """
                {"mappings": [
                  {"header": "Player Name",   "field": "ATHLETE_NAME"},
                  {"header": "Email",         "field": "ATHLETE_EMAIL"},
                  {"header": "Player Id",     "field": "ATHLETE_EXTERNAL_ID"},
                  {"header": "DOB",           "field": "ATHLETE_DOB"},
                  {"header": "Date",          "field": "RECORDED_AT"},
                  {"header": "Session Id",    "field": "RECORD_ID"},
                  {"header": "Total Distance","field": "GPS_TOTAL_DISTANCE_M", "unit": "m"},
                  {"header": "Max Vel (km/h)","field": "GPS_MAX_VELOCITY_KMH", "unit": "km/h"}
                ]}
                """);
        mappingService.upsertProfile("vald-forcedecks-csv", EntityType.FORCE, "VALD", """
                {"mappings": [
                  {"header": "Name",          "field": "ATHLETE_NAME"},
                  {"header": "Email",         "field": "ATHLETE_EMAIL"},
                  {"header": "Profile Id",    "field": "ATHLETE_EXTERNAL_ID"},
                  {"header": "Test Date",     "field": "RECORDED_AT"},
                  {"header": "Test Type",     "field": "TEST_TYPE"},
                  {"header": "Test Id",       "field": "RECORD_ID"}
                ]}
                """);
        mappingService.upsertProfile("wellness-csv", EntityType.WELLNESS, "CSV_LEGACY", """
                {"mappings": [
                  {"header": "Athlete",       "field": "ATHLETE_NAME"},
                  {"header": "Email",         "field": "ATHLETE_EMAIL"},
                  {"header": "Survey Date",   "field": "RECORDED_AT"}
                ]}
                """);
        log.info("seeded default column-mapping profiles (catapult-session-csv, vald-forcedecks-csv, wellness-csv)");
    }
}
