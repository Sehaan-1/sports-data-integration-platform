package com.sdip.api;

import com.sdip.SdipApplication;
import com.sdip.api.demo.DemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo path must be populated on first start and must stay stable on every
 * later start (Review Focus 1: a reader will restart the container).
 */
@SpringBootTest(
        classes = SdipApplication.class,
        properties = {
                "sdip.seed.demo-data=true",
                // Isolated database: this context seeds rows, and every other
                // sdip-api test shares jdbc:h2:mem:sdip-api-test.
                "spring.datasource.url=jdbc:h2:mem:sdip-demo-seed;MODE=PostgreSQL;"
                        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        })
class DemoDataSeederTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedsFourAthletesAndBothMetricFamiliesOnStartup() {
        assertThat(count("athlete")).isEqualTo(4);
        assertThat(count("gps_metric")).isGreaterThan(0);
        assertThat(count("force_test")).isGreaterThan(0);
    }

    @Test
    void secondRunDoesNotDuplicateAnything() {
        int gps = count("gps_metric");
        int force = count("force_test");
        int batches = count("ingestion_batch");

        seeder.run(null);

        assertThat(count("athlete")).isEqualTo(4);
        assertThat(count("gps_metric")).isEqualTo(gps);
        assertThat(count("force_test")).isEqualTo(force);
        assertThat(count("ingestion_batch")).isEqualTo(batches);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
