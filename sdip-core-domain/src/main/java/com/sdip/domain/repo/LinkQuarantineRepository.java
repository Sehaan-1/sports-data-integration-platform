package com.sdip.domain.repo;

import com.sdip.domain.entity.LinkQuarantine;
import com.sdip.domain.entity.LinkQuarantine.QuarantineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LinkQuarantineRepository extends JpaRepository<LinkQuarantine, UUID> {

    Page<LinkQuarantine> findByStatusOrderByCreatedAtDesc(QuarantineStatus status, Pageable pageable);

    long countByStatus(QuarantineStatus status);

    List<LinkQuarantine> findBySourceSystemAndSourceAthleteId(
            String sourceSystem, String sourceAthleteId);
}
