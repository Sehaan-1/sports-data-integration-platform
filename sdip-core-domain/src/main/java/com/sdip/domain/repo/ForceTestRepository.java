package com.sdip.domain.repo;

import com.sdip.domain.entity.ForceTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ForceTestRepository extends JpaRepository<ForceTest, UUID> {

    Optional<ForceTest> findBySourceSystemAndSourceRecordId(String sourceSystem, String sourceRecordId);

    long countByAthleteId(UUID athleteId);
}
