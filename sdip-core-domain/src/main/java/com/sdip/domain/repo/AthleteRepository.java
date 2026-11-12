package com.sdip.domain.repo;

import com.sdip.domain.entity.Athlete;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AthleteRepository extends JpaRepository<Athlete, UUID> {

    Optional<Athlete> findByEmailIgnoreCase(String email);

    /** Squad-scoped deterministic email lookup (Tier-1b; email unique per squad). */
    Optional<Athlete> findFirstByEmailIgnoreCaseAndSquad_Id(String email, UUID squadId);

    /** DOB-year blocking key for the heuristic matching tier (§4.3). */
    @Query("select a from Athlete a where a.dob is not null and year(a.dob) = :birthYear")
    List<Athlete> findByBirthYear(@Param("birthYear") int birthYear);

    Page<Athlete> findBySquadId(UUID squadId, Pageable pageable);
}
