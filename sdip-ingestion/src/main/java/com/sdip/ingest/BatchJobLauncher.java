package com.sdip.ingest;

import com.sdip.domain.entity.ColumnMappingProfile;
import com.sdip.domain.entity.EntityType;
import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.entity.IngestionBatch.BatchType;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * API-facing entry point for file ingestion. Registers the lineage row FIRST
 * (so every launched job is traceable), then launches the Spring Batch job —
 * asynchronously for HTTP uploads, synchronously for tests.
 */
@Service
public class BatchJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(BatchJobLauncher.class);

    private final JobLauncher jobLauncher;
    private final Job fileImportJob;
    private final IngestionBatchRepository batches;
    private final ColumnMappingService mappingService;
    private final AuditService audit;
    private final Executor executor;

    public BatchJobLauncher(JobLauncher jobLauncher,
                            @Qualifier(FileImportJobConfig.JOB_NAME) Job fileImportJob,
                            IngestionBatchRepository batches,
                            ColumnMappingService mappingService,
                            AuditService audit,
                            @Qualifier("applicationTaskExecutor") Executor executor) {
        this.jobLauncher = jobLauncher;
        this.fileImportJob = fileImportJob;
        this.batches = batches;
        this.mappingService = mappingService;
        this.audit = audit;
        this.executor = executor;
    }

    /** Register lineage and launch asynchronously. Returns the batch id immediately. */
    public UUID launchAsync(Path file, String profileName, UUID squadId, String actorEmail, String actorRole) {
        IngestionBatch batch = register(file, profileName, actorEmail, actorRole);
        JobParameters params = parameters(file, profileName, squadId, batch.getId());
        CompletableFuture.runAsync(() -> run(params), executor);
        return batch.getId();
    }

    /** Synchronous variant used by tests and benchmarks. Blocks until the job finishes. */
    public UUID launchSync(Path file, String profileName, UUID squadId, String actorEmail, String actorRole) {
        IngestionBatch batch = register(file, profileName, actorEmail, actorRole);
        run(parameters(file, profileName, squadId, batch.getId()));
        return batch.getId();
    }

    private IngestionBatch register(Path file, String profileName, String actorEmail, String actorRole) {
        ColumnMappingProfile profile = mappingService.requireProfile(profileName);
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        BatchType type = (lower.endsWith(".xlsx") || lower.endsWith(".xlsm"))
                ? BatchType.FILE_XLSX : BatchType.FILE_CSV;
        IngestionBatch batch = new IngestionBatch(type, profile.getSourceSystem(),
                file.getFileName().toString(), actorEmail);
        batches.save(batch);
        audit.record(actorEmail, actorRole, "FILE_UPLOAD", "IngestionBatch", batch.getId().toString(),
                java.util.Map.of("file", batch.getFileName(), "profile", profileName, "type", type.name()));
        return batch;
    }

    private JobParameters parameters(Path file, String profileName, UUID squadId, UUID batchId) {
        return new JobParametersBuilder()
                .addString("filePath", file.toAbsolutePath().toString())
                .addString("profileName", profileName)
                .addString("squadId", squadId.toString())
                .addString("batchId", batchId.toString())
                // uniqueness token so Spring Batch permits re-launches; DATA idempotency is
                // guaranteed independently by natural-key upserts (C4)
                .addString("launchId", UUID.randomUUID().toString())
                .toJobParameters();
    }

    private void run(JobParameters params) {
        try {
            jobLauncher.run(fileImportJob, params);
        } catch (Exception e) {
            log.error("file import job failed to launch/complete: {}", e.getMessage(), e);
            markFailed(params, e);
        }
    }

    private void markFailed(JobParameters params, Exception e) {
        String batchId = params.getString("batchId");
        if (batchId == null) {
            return;
        }
        try {
            batches.findById(UUID.fromString(batchId)).ifPresent(b -> {
                b.finish(IngestionBatch.BatchStatus.FAILED, 0, 0, 0,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                batches.save(b);
            });
        } catch (Exception inner) {
            log.error("could not mark batch failed: {}", inner.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public EntityType profileEntityType(String profileName) {
        return mappingService.requireProfile(profileName).getEntityType();
    }
}
