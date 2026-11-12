package com.sdip.ingest;

import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.entity.IngestionBatch.BatchStatus;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.repo.IngestionRowErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maintains the immutable {@code ingestion_batch_log} (§4.4): one row per
 * ingest with rows read/written/skipped, status, duration, and an error
 * digest — every fact row is traceable to its batch.
 */
@Component
public class BatchBookkeepingListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(BatchBookkeepingListener.class);

    private final IngestionBatchRepository batches;
    private final IngestionRowErrorRepository rowErrors;

    public BatchBookkeepingListener(IngestionBatchRepository batches, IngestionRowErrorRepository rowErrors) {
        this.batches = batches;
        this.rowErrors = rowErrors;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String batchIdParam = jobExecution.getJobParameters().getString("batchId");
        if (batchIdParam == null) {
            return;
        }
        UUID batchId = UUID.fromString(batchIdParam);
        long read = 0, written = 0, skipped = 0, filtered = 0;
        for (StepExecution se : jobExecution.getStepExecutions()) {
            read += se.getReadCount();
            written += se.getWriteCount();
            skipped += se.getSkipCount();
            filtered += se.getFilterCount();
        }
        final long totalRead = read, totalWritten = written;
        final long totalSkipped = skipped + filtered;
        final long rowErrorCount = rowErrors.countByBatchId(batchId);

        final BatchStatus status;
        if (jobExecution.getStatus() != org.springframework.batch.core.BatchStatus.COMPLETED) {
            status = BatchStatus.FAILED;
        } else {
            status = (totalSkipped + rowErrorCount) > 0
                    ? BatchStatus.COMPLETED_WITH_SKIPS : BatchStatus.COMPLETED;
        }
        String errorDigest = jobExecution.getAllFailureExceptions().stream()
                .map(e -> e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()))
                .distinct()
                .limit(5)
                .collect(Collectors.joining(" | "));

        batches.findById(batchId).ifPresent(b -> {
            b.finish(status, totalRead, totalWritten, totalSkipped, errorDigest.isBlank() ? null : errorDigest);
            batches.save(b);
            log.info("batch {} finished [{}]: read={} written={} skipped={} rowErrors={}",
                    batchId, status, totalRead, totalWritten, totalSkipped, rowErrorCount);
        });
    }
}
