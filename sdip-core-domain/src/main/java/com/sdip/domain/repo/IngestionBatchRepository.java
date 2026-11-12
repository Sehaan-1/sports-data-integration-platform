package com.sdip.domain.repo;

import com.sdip.domain.entity.IngestionBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, UUID> {

    Page<IngestionBatch> findAllByOrderByStartedAtDesc(Pageable pageable);
}
