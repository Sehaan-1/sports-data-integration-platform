package com.sdip.domain.repo;

import com.sdip.domain.entity.WellnessSurvey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WellnessSurveyRepository extends JpaRepository<WellnessSurvey, UUID> {

    Optional<WellnessSurvey> findBySourceSystemAndSourceRecordId(String sourceSystem, String sourceRecordId);

    long countByAthleteId(UUID athleteId);
}
