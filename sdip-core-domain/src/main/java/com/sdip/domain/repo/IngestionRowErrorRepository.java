package com.sdip.domain.repo;

import com.sdip.domain.entity.IngestionRowError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionRowErrorRepository extends JpaRepository<IngestionRowError, Long> {

    List<IngestionRowError> findByBatchIdOrderByRowNumber(UUID batchId);

    long countByBatchId(UUID batchId);
}
