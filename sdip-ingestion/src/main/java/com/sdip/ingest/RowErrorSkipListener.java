package com.sdip.ingest;

import com.sdip.domain.service.RowErrorRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;

import java.util.UUID;

/**
 * Captures every skipped row into {@code ingestion_row_error} — including the
 * row number, raw content, and violation — via a REQUIRES_NEW transaction so
 * the evidence survives chunk rollback (§4.2, RQ1 criterion 3).
 */
public class RowErrorSkipListener implements SkipListener<RowEnvelope, ImportChunk> {

    private static final Logger log = LoggerFactory.getLogger(RowErrorSkipListener.class);

    private final RowErrorRecorder recorder;
    private final UUID batchId;

    public RowErrorSkipListener(RowErrorRecorder recorder, UUID batchId) {
        this.recorder = recorder;
        this.batchId = batchId;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        recordSkip(-1L, t);
    }

    @Override
    public void onSkipInProcess(RowEnvelope item, Throwable t) {
        recordSkip(item != null ? item.rowNumber() : -1L, t,
                item != null ? item.raw() : null);
    }

    @Override
    public void onSkipInWrite(ImportChunk item, Throwable t) {
        recordSkip(-1L, t);
    }

    private void recordSkip(long rowNumber, Throwable t) {
        recordSkip(rowNumber, t, null);
    }

    private void recordSkip(long rowNumber, Throwable t, String raw) {
        Throwable root = t;
        while (root.getCause() instanceof RowViolationException rve) {
            root = rve;
        }
        long effectiveRow = rowNumber;
        String effectiveRaw = raw;
        if (root instanceof RowViolationException rve) {
            if (rve.rowNumber() != null) {
                effectiveRow = rve.rowNumber();
            }
            if (rve.rawContent() != null && effectiveRaw == null) {
                effectiveRaw = rve.rawContent();
            }
        }
        log.warn("row {} skipped: {}", effectiveRow, root.getMessage());
        recorder.record(batchId, (int) effectiveRow, effectiveRaw,
                root.getClass().getSimpleName() + ": " + root.getMessage());
    }
}
