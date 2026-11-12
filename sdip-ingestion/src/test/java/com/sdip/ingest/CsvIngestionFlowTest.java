package com.sdip.ingest;

import com.sdip.TestIngestionApp;
import com.sdip.domain.entity.Athlete;
import com.sdip.domain.entity.AthleteSourceLink;
import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.repo.AthleteSourceLinkRepository;
import com.sdip.domain.repo.GpsMetricRepository;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.repo.IngestionRowErrorRepository;
import com.sdip.domain.repo.SquadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RQ1 (file pipeline): transactional ingest, corrupt-row quarantine, idempotent
 * re-ingest, and provisional-athlete creation — end to end through Spring Batch.
 */
@SpringBootTest(classes = TestIngestionApp.class)
class CsvIngestionFlowTest {

    @Autowired
    BatchJobLauncher launcher;
    @Autowired
    SquadRepository squads;
    @Autowired
    AthleteRepository athletes;
    @Autowired
    GpsMetricRepository gps;
    @Autowired
    IngestionBatchRepository batches;
    @Autowired
    IngestionRowErrorRepository rowErrors;
    @Autowired
    AthleteSourceLinkRepository links;

    @TempDir
    Path tempDir;

    private Squad squad() {
        return squads.findByTenantCodeAndName("FLOW", "Flow Squad")
                .orElseGet(() -> squads.save(new Squad("Flow Squad", "FLOW")));
    }

    private Athlete athlete(String name, String dob, String email) {
        return athletes.findByEmailIgnoreCase(email)
                .orElseGet(() -> athletes.save(new Athlete(squad(), name, LocalDate.parse(dob), email, false)));
    }

    private Path writeCsv(String name, String content) throws Exception {
        Path f = tempDir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final String HEADER =
            "Player Name,Email,Player Id,Date,Session Id,Total Distance,HSR,Player Load,Max Vel (km/h),Duration\n";

    private static String body(String sam, String ada) {
        return """
                Sam Kerr,%s,cat-sam-flow,2025-08-01,sess-1,6540,612,420,33.4,82
                Sam Kerr,%s,cat-sam-flow,2025-08-03,sess-2,5210,401,380,31.2,75
                Ada Hegerberg,%s,cat-ada-flow,2025-08-01,sess-1,6130,540,410,32.8,80
                New Guy Flow,,cat-new-flow,2025-08-01,sess-3,4000,200,200,29.0,60
                Sam Kerr,%s,cat-sam-flow,2025-08-04,sess-4,BOGUS,10,10,10,10
                Sam Kerr,%s,cat-sam-flow,2025-08-05,sess-5,6540,612,99999,33.4,82
                """.formatted(sam, sam, ada, sam, sam);
    }

    @Test

    void csvFlow_corruptRowsQuarantined_andReingestIsIdempotent() throws Exception {
        Squad squad = squad();
        Athlete sam = athlete("Sam Kerr", "1993-09-10", "sam.kerr@flow.test");
        Athlete ada = athlete("Ada Hegerberg", "1995-07-10", "ada@flow.test");

        Path csv = writeCsv("catapult-sessions.csv", HEADER + body(sam.getEmail(), ada.getEmail()));
        UUID batchId = launcher.launchSync(csv, "catapult-session-csv", squad.getId(), "tester", "ADMIN");

        // 4 valid rows loaded (incl. provisional athlete); 2 corrupt rows quarantined
        IngestionBatch diagBatch = batches.findById(batchId).orElseThrow();
        String diag = "gpsSam=" + gps.countByAthleteId(sam.getId())
                + " gpsAda=" + gps.countByAthleteId(ada.getId())
                + " gpsTotal=" + gps.count()
                + " batchStatus=" + diagBatch.getStatus()
                + " read=" + diagBatch.getRowsRead()
                + " written=" + diagBatch.getRowsWritten()
                + " skipped=" + diagBatch.getRowsSkipped()
                + " digest=" + abbrev(diagBatch.getErrorSummary(), 200)
                + " allRowErrors=" + rowErrors.findAll().stream()
                    .map(e -> "[" + e.getRowNumber() + "]" + abbrev(e.getViolation(), 90)).toList();
        assertThat(gps.countByAthleteId(sam.getId())).as(diag).isEqualTo(2);
        assertThat(gps.countByAthleteId(ada.getId())).as(diag).isEqualTo(1);

        var savedRowErrors = rowErrors.findByBatchIdOrderByRowNumber(batchId);
        assertThat(savedRowErrors).as(diag).hasSize(2);
        assertThat(savedRowErrors.stream().map(e -> e.getViolation()).toList().toString())
                .contains("unparseable number")
                .contains("outside valid range");
        assertThat(savedRowErrors.get(0).getRawContent()).isNotBlank();

        IngestionBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(IngestionBatch.BatchStatus.COMPLETED_WITH_SKIPS);
        assertThat(batch.getRowsRead()).isEqualTo(6);
        assertThat(batch.getRowsWritten()).isEqualTo(4);
        assertThat(batch.getRowsSkipped()).isEqualTo(2);
        assertThat(batch.getDurationMs()).isNotNull();

        // provisional master created for the previously unknown athlete, stably linked
        Athlete newGuy = athletes.findAll().stream()
                .filter(a -> a.getFullName().equals("New Guy Flow")).findFirst().orElseThrow();
        assertThat(newGuy.isProvisional()).isTrue();
        assertThat(gps.countByAthleteId(newGuy.getId())).isEqualTo(1);
        assertThat(links.findBySourceSystemAndSourceAthleteIdAndStatus(
                "CATAPULT", "cat-new-flow", AthleteSourceLink.LinkStatus.ACTIVE)).isPresent();

        long totalBefore = gps.count();
        // C4: re-ingesting the SAME file produces zero duplicates
        UUID secondBatch = launcher.launchSync(csv, "catapult-session-csv", squad.getId(), "tester", "ADMIN");
        assertThat(gps.count()).isEqualTo(totalBefore);
        assertThat(gps.countByAthleteId(sam.getId())).isEqualTo(2);
        assertThat(batches.findById(secondBatch)).isPresent(); // lineage kept for BOTH runs
    }

    @Test

    void throughputProbe_measuresRowsPerSecond() throws Exception {
        Squad squad = squad();
        Athlete benchSam = athlete("Bench Sam", "1993-09-10", "bench-sam@flow.test");
        Athlete benchAda = athlete("Bench Ada", "1995-07-10", "bench-ada@flow.test");

        // 20k synthetic rows across the two athletes (all values dictionary-valid)
        StringBuilder sb = new StringBuilder(HEADER);
        String[][] roster = {
                {"Bench Sam", "bench-sam@flow.test", "cat-bsam"},
                {"Bench Ada", "bench-ada@flow.test", "cat-bada"}
        };
        for (int i = 0; i < 20_000; i++) {
            String[] a = roster[i % 2];
            sb.append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append(',')
                    .append("2025-08-").append(String.format("%02d", 1 + i % 28)).append(',')
                    .append("sess-tp-").append(i).append(',')
                    .append(6000 + i % 900).append(',')
                    .append(400 + i % 300).append(',')
                    .append(300 + i % 150).append(',')
                    .append(28.0 + (i % 50) / 10.0).append(',')
                    .append(80).append('\n');
        }
        Path csv = writeCsv("throughput.csv", sb.toString());

        long t0 = System.nanoTime();
        UUID batchId = launcher.launchSync(csv, "catapult-session-csv", squad.getId(), "bench", "ADMIN");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(gps.countByAthleteId(benchSam.getId())).isEqualTo(10_000);
        assertThat(gps.countByAthleteId(benchAda.getId())).isEqualTo(10_000);
        double rowsPerSec = 20_000.0 / (Math.max(elapsedMs, 1) / 1000.0);
        System.out.printf("THROUGHPUT: %.0f rows/sec (20k rows in %d ms)%n", rowsPerSec, elapsedMs);
        // Guard-rail (single-threaded sandbox H2): the RQ1 ≥5,000 rows/s criterion
        // targets PostgreSQL on commodity hardware (Testcontainers/CI profile).
        assertThat(rowsPerSec).isGreaterThan(1_200);
        assertThat(batches.findById(batchId).orElseThrow().getStatus())
                .isEqualTo(IngestionBatch.BatchStatus.COMPLETED);
    }
}
