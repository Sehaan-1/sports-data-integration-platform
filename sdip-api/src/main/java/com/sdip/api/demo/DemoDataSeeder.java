package com.sdip.api.demo;

import com.sdip.api.IngestionAdminService;
import com.sdip.domain.entity.AppUser;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AppUserRepository;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.security.UserSeedRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Demo bootstrap: pushes the checked-in vendor fixtures through the real
 * ingestion pipeline so a freshly started stack already has athletes, GPS
 * metrics and force tests on the dashboard.
 *
 * <p>Gated by {@code sdip.seed.demo-data} (default false). The seeder is
 * synchronous and guarded by an existence check, so a container restart never
 * duplicates a batch. It runs last so the default users and column-mapping
 * profiles it depends on already exist.
 */
@Component
@Order(100)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final String DEMO_SQUAD = "A Team";

    private final IngestionAdminService ingestion;
    private final AppUserRepository users;
    private final SquadRepository squads;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DemoDataSeeder(IngestionAdminService ingestion,
                          AppUserRepository users,
                          SquadRepository squads,
                          JdbcTemplate jdbc,
                          @Value("${sdip.seed.demo-data:false}") boolean enabled) {
        this.ingestion = ingestion;
        this.users = users;
        this.squads = squads;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Integer existing = jdbc.queryForObject("select count(*) from gps_metric", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Demo dataset already present ({} GPS rows) — not re-seeding.", existing);
            return;
        }
        AppUser admin = users.findByEmailIgnoreCase("admin@sdip.local")
                .orElseThrow(() -> new IllegalStateException(
                        "demo seeding needs the default admin user; keep sdip.seed.default-users=true"));
        Squad squad = squads.findByTenantCodeAndName(UserSeedRunner.TENANT, DEMO_SQUAD)
                .orElseThrow(() -> new IllegalStateException(
                        "demo seeding needs the '" + DEMO_SQUAD + "' squad"));

        seed("demo/catapult-sessions.csv", "catapult-session-csv", squad.getId(), admin);
        seed("demo/vald-forcedecks.csv", "vald-forcedecks-csv", squad.getId(), admin);
        log.info("Seeded demo dataset (4 athletes).");
    }

    private void seed(String resource, String profileName, UUID squadId, AppUser actor) {
        try {
            MultipartFile file = new ClasspathCsv(resource, new ClassPathResource(resource).getContentAsByteArray());
            ingestion.ingestSync(file, profileName, squadId, actor.getEmail(), actor.getRole().name());
        } catch (IOException e) {
            throw new IllegalStateException("could not read demo fixture " + resource, e);
        }
    }

    /** Minimal in-memory {@link MultipartFile}; the ingestion service only reads bytes and the name. */
    private record ClasspathCsv(String filename, byte[] content) implements MultipartFile {

        @Override
        public String getName() {
            return filename;
        }

        @Override
        public String getOriginalFilename() {
            return filename.substring(filename.lastIndexOf('/') + 1);
        }

        @Override
        public String getContentType() {
            return "text/csv";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
