package com.sdip.domain.service;

import com.sdip.domain.entity.IngestionRowError;
import com.sdip.domain.repo.IngestionRowErrorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Captures per-row ingestion errors in a SEPARATE transaction
 * (REQUIRES_NEW): when a chunk rolls back, the evidence of which rows were
 * skipped — and why — survives (§4.2: "partial-file outcomes are always
 * visible, never silent").
 */
@Service
public class RowErrorRecorder {

    private final IngestionRowErrorRepository repository;

    public RowErrorRecorder(IngestionRowErrorRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID batchId, int rowNumber, String rawContent, String violation) {
        repository.save(new IngestionRowError(batchId, rowNumber, rawContent, violation));
    }
}
