package com.sdip.domain.repo;

import com.sdip.domain.entity.TrainingSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, UUID> {
}
