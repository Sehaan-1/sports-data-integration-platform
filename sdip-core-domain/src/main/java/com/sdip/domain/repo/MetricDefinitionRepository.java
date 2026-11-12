package com.sdip.domain.repo;

import com.sdip.domain.entity.MetricDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricDefinitionRepository extends JpaRepository<MetricDefinition, String> {
}
