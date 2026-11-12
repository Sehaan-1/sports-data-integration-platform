package com.sdip.domain.repo;

import com.sdip.domain.entity.Squad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SquadRepository extends JpaRepository<Squad, UUID> {

    Optional<Squad> findByTenantCodeAndName(String tenantCode, String name);
}
