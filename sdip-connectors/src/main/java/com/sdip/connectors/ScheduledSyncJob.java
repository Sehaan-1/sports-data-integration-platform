package com.sdip.connectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Polling-consumer trigger (§4.1): every configured interval, each ENABLED
 * connector runs its watermarked incremental sync. Disabled by default — flip
 * {@code sdip.connectors.scheduling-enabled=true} per deployment.
 */
@Component
@ConditionalOnProperty(name = "sdip.connectors.scheduling-enabled", havingValue = "true")
public class ScheduledSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncJob.class);

    private final ConnectorProperties properties;
    private final SyncOrchestrator orchestrator;

    public ScheduledSyncJob(ConnectorProperties properties, SyncOrchestrator orchestrator) {
        this.properties = properties;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${sdip.connectors.poll-interval:PT15M}",
            initialDelayString = "PT30S")
    public void tick() {
        for (Map.Entry<String, ConnectorProperties.Vendor> entry : properties.getVendors().entrySet()) {
            if (!entry.getValue().isEnabled()) {
                continue;
            }
            SyncReport report = orchestrator.sync(entry.getKey());
            log.info("scheduled sync {}: status={} read={} written={} quarantined={}",
                    report.vendor(), report.status(), report.recordsRead(),
                    report.recordsWritten(), report.recordsQuarantined());
        }
    }
}
