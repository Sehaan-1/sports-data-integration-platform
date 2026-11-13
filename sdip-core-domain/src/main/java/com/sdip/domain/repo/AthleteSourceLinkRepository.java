package com.sdip.domain.repo;

import com.sdip.domain.entity.AthleteSourceLink;
import com.sdip.domain.entity.AthleteSourceLink.LinkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AthleteSourceLinkRepository extends JpaRepository<AthleteSourceLink, UUID> {

    Optional<AthleteSourceLink> findBySourceSystemAndSourceAthleteIdAndStatus(
            String sourceSystem, String sourceAthleteId, LinkStatus status);

    List<AthleteSourceLink> findBySourceSystemAndSourceAthleteId(
            String sourceSystem, String sourceAthleteId);

    List<AthleteSourceLink> findByAthleteIdAndStatus(UUID athleteId, LinkStatus status);
}
