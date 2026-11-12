package com.sdip.connectors;

import com.sdip.connectors.VendorDtos.CatapultActivitiesResponse;
import com.sdip.connectors.VendorDtos.CatapultActivity;
import com.sdip.connectors.VendorDtos.CatapultAthlete;
import com.sdip.connectors.VendorDtos.CatapultAthletesResponse;
import com.sdip.connectors.VendorDtos.ValdTest;
import com.sdip.connectors.VendorDtos.ValdTestsResponse;
import com.sdip.domain.link.EntityLinker;
import com.sdip.domain.link.IdentityRecord;
import com.sdip.domain.link.LinkOutcome;
import com.sdip.domain.entity.ConnectorState;
import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.repo.ConnectorStateRepository;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.service.AuditService;
import com.sdip.domain.service.CanonicalImportService;
import com.sdip.domain.service.ImportRecords.ForceImport;
import com.sdip.domain.service.ImportRecords.GpsImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watermarked incremental synchronization (§4.1 / C4):
 * <ul>
 *   <li>each run fetches <code>[watermark − overlap, now]</code> so late-arriving
 *       vendor edits inside the overlap window are re-pulled (and absorbed by
 *       idempotent upserts — zero duplicates on replays);</li>
 *   <li>the watermark advances only on a fully successful run; failures leave
 *       it untouched so the next run retries the whole window;</li>
 *   <li>HTTP calls are made through Resilience4j retry / circuit-breaker /
 *       rate-limiter decorators (RQ4).</li>
 * </ul>
 */
@Service
public class SyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrator.class);
    private static final String SYNC_ACTOR = "connector-scheduler";

    private final ConnectorProperties properties;
    private final ConnectorStateRepository states;
    private final IngestionBatchRepository batches;
    private final CanonicalImportService importer;
    private final EntityLinker linker;
    private final AuditService audit;
    private final ResilienceSupport resilience;

    public SyncOrchestrator(ConnectorProperties properties,
                            ConnectorStateRepository states,
                            IngestionBatchRepository batches,
                            CanonicalImportService importer,
                            EntityLinker linker,
                            AuditService audit,
                            ResilienceSupport resilience) {
        this.properties = properties;
        this.states = states;
        this.batches = batches;
        this.importer = importer;
        this.linker = linker;
        this.audit = audit;
        this.resilience = resilience;
    }

    /** Run one incremental sync for the given vendor key ("catapult" | "vald"). */
    public SyncReport sync(String vendorKey) {
        ConnectorProperties.Vendor props = properties.getVendors().get(vendorKey);
        if (props == null) {
            // No configuration block at all → the connector is simply off.
            return SyncReport.disabled(vendorKey);
        }
        if (!props.isEnabled()) {
            return SyncReport.disabled(vendorKey);
        }
        // Immutable ingestion log row for this sync (§4.4): every fact row's
        // ingestion_batch_id FK points here.
        String sourceSystem = vendorKey.equals("vald") ? "VALD" : "CATAPULT";
        IngestionBatch batch = batches.save(new IngestionBatch(
                IngestionBatch.BatchType.CONNECTOR, sourceSystem, null, SYNC_ACTOR));
        UUID batchId = batch.getId();
        Instant to = Instant.now();
        ConnectorState state = states.findById(vendorKey).orElse(new ConnectorState(vendorKey));
        Instant from = state.getLastSyncedAt() != null
                ? state.getLastSyncedAt().minus(Duration.ofHours(props.getOverlapHours()))
                : to.minus(Duration.ofHours(props.getInitialLookbackHours()));

        try {
            SyncReport report = switch (vendorKey) {
                case "catapult" -> syncCatapult(props, from, to, batchId);
                case "vald" -> syncVald(props, from, to, batchId);
                default -> SyncReport.degraded(vendorKey, from, to, "unknown vendor kind '" + vendorKey + "'");
            };
            if (report.status() == SyncReport.Status.OK) {
                state.advanceTo(to);
                states.save(state);
            }
            IngestionBatch.BatchStatus batchStatus =
                    switch (report.status()) {
                        case OK -> report.recordsQuarantined() > 0
                                ? IngestionBatch.BatchStatus.COMPLETED_WITH_SKIPS
                                : IngestionBatch.BatchStatus.COMPLETED;
                        default -> IngestionBatch.BatchStatus.FAILED;
                    };
            batch.finish(batchStatus, report.recordsRead(), report.recordsWritten(),
                    report.recordsQuarantined(), report.error());
            batches.save(batch);
            audit.record(SYNC_ACTOR, "SYSTEM",
                    report.status() == SyncReport.Status.OK ? "CONNECTOR_SYNC" : "CONNECTOR_SYNC_FAILED",
                    "ConnectorState", vendorKey,
                    Map.of("vendor", vendorKey,
                            "from", from.toString(), "to", to.toString(),
                            "read", report.recordsRead(), "written", report.recordsWritten(),
                            "quarantined", report.recordsQuarantined(),
                            "error", report.error() != null ? report.error() : ""));
            return report;
        } catch (Exception e) {
            log.warn("sync of {} degraded: {}", vendorKey, e.getMessage());
            batch.finish(IngestionBatch.BatchStatus.FAILED, 0, 0, 0,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            batches.save(batch);
            audit.record(SYNC_ACTOR, "SYSTEM", "CONNECTOR_SYNC_FAILED", "ConnectorState", vendorKey,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return SyncReport.degraded(vendorKey, from, to, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Catapult OpenField
    // ------------------------------------------------------------------

    private SyncReport syncCatapult(ConnectorProperties.Vendor props, Instant from, Instant to, UUID batchId) {
        VendorHttpClient client = new VendorHttpClient(props);

        // 1) Directory of athletes (linked once per run)
        CatapultAthletesResponse directory = resilience.execute("catapult", props.getRequestsPerSecond(),
                () -> client.get("/openfield/api/v1/athletes", CatapultAthletesResponse.class));
        Map<String, CatapultAthlete> byId = new HashMap<>();
        if (directory != null && directory.athletes() != null) {
            directory.athletes().forEach(a -> byId.put(a.id(), a));
        }

        // 2) Resolve each athlete through the tiered linker
        Map<String, UUID> athleteIds = new HashMap<>();
        int quarantined = 0;
        for (CatapultAthlete a : byId.values()) {
            LinkOutcome outcome = linker.resolve(new IdentityRecord("CATAPULT", a.id(),
                    a.fullName(),
                    a.dateOfBirth() != null && !a.dateOfBirth().isBlank()
                            ? LocalDate.parse(a.dateOfBirth()) : null,
                    a.email(), props.getSquadId()));
            if (outcome.linkable()) {
                athleteIds.put(a.id(), outcome.athleteId());
            } else {
                quarantined++;
            }
        }

        // 3) Page through activities in the window
        int read = 0, written = 0;
        int page = 0, totalPages = 1;
        while (page < totalPages) {
            final int p = page;
            CatapultActivitiesResponse resp = resilience.execute("catapult", props.getRequestsPerSecond(),
                    () -> client.get("/openfield/api/v1/activities",
                            ub -> ub.queryParam("start_date", from.toString())
                                    .queryParam("end_date", to.toString())
                                    .queryParam("page", p)
                                    .queryParam("size", props.getPageSize()).build(),
                            CatapultActivitiesResponse.class));
            List<CatapultActivity> activities = resp != null && resp.activities() != null
                    ? resp.activities() : List.of();
            totalPages = resp != null && resp.page() != null ? resp.page().totalPages() : 1;
            read += activities.size();

            Instant syncedAt = Instant.now();
            UUID squadId = props.getSquadId();
            List<GpsImport> rows = new ArrayList<>();
            for (CatapultActivity act : activities) {
                UUID athleteId = athleteIds.get(act.athleteId());
                if (athleteId == null) {
                    continue; // athlete unresolved (quarantined) — counted once above
                }
                rows.add(new GpsImport(athleteId, squadId,
                        Instant.parse(act.startTime()),
                        act.totalDistance(), act.highSpeedRunning(), act.playerLoad(),
                        act.maxVelocity(), act.durationMinutes(),
                        "CATAPULT", act.id(), batchId, syncedAt));
            }
            if (!rows.isEmpty()) {
                written += importer.upsertGps(rows);
            }
            page++;
        }
        return SyncReport.ok("catapult", from, to, read, written, quarantined);
    }

    // ------------------------------------------------------------------
    // VALD Hub (ForceDecks)
    // ------------------------------------------------------------------

    private SyncReport syncVald(ConnectorProperties.Vendor props, Instant from, Instant to, UUID batchId) {
        VendorHttpClient client = new VendorHttpClient(props);
        int read = 0, written = 0, quarantined = 0;
        int page = 0, totalPages = 1;
        while (page < totalPages) {
            final int p = page;
            ValdTestsResponse resp = resilience.execute("vald", props.getRequestsPerSecond(),
                    () -> client.get("/vald/api/v1/tests",
                            ub -> ub.queryParam("modified_since", from.toString())
                                    .queryParam("page", p)
                                    .queryParam("size", props.getPageSize()).build(),
                            ValdTestsResponse.class));
            List<ValdTest> tests = resp != null && resp.tests() != null ? resp.tests() : List.of();
            totalPages = resp != null && resp.page() != null ? resp.page().totalPages() : 1;
            read += tests.size();

            Instant syncedAt = Instant.now();
            List<ForceImport> rows = new ArrayList<>();
            for (ValdTest t : tests) {
                LinkOutcome outcome = linker.resolve(new IdentityRecord("VALD", t.profileId(),
                        t.athleteName(), null, t.email(), props.getSquadId()));
                if (!outcome.linkable()) {
                    quarantined++;
                    continue;
                }
                rows.add(new ForceImport(outcome.athleteId(), props.getSquadId(),
                        t.testType() != null ? t.testType() : "UNKNOWN",
                        Instant.parse(t.recordedUtc()),
                        t.peakForceN(), t.asymmetryPct(), t.rsiModified(), t.peakPowerW(),
                        "VALD", t.testId(), batchId, syncedAt));
            }
            if (!rows.isEmpty()) {
                written += importer.upsertForce(rows);
            }
            page++;
        }
        return SyncReport.ok("vald", from, to, read, written, quarantined);
    }
}
