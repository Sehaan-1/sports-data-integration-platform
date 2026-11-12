package com.sdip.domain.repo;

import com.sdip.domain.entity.GpsMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GpsMetricRepository extends JpaRepository<GpsMetric, UUID> {

    Optional<GpsMetric> findBySourceSystemAndSourceRecordId(String sourceSystem, String sourceRecordId);

    long countByAthleteId(UUID athleteId);
}
