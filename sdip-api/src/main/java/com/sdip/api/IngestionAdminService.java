package com.sdip.api;

import com.sdip.domain.entity.ColumnMappingProfile;
import com.sdip.domain.entity.EntityType;
import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.entity.IngestionRowError;
import com.sdip.domain.repo.IngestionBatchRepository;
import com.sdip.domain.repo.IngestionRowErrorRepository;
import com.sdip.domain.service.RowScopeGuard;
import com.sdip.ingest.BatchJobLauncher;
import com.sdip.ingest.ColumnMappingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Upload staging + ingestion administration. */
@Service
public class IngestionAdminService {

    private final Path uploadDir;
    private final BatchJobLauncher launcher;
    private final IngestionBatchRepository batches;
    private final IngestionRowErrorRepository rowErrors;
    private final ColumnMappingService mappingService;
    private final RowScopeGuard scope;

    public IngestionAdminService(@Value("${sdip.ingestion.upload-dir:${java.io.tmpdir}/sdip-uploads}") String uploadDir,
                                 BatchJobLauncher launcher,
                                 IngestionBatchRepository batches,
                                 IngestionRowErrorRepository rowErrors,
                                 ColumnMappingService mappingService,
                                 RowScopeGuard scope) {
        this.uploadDir = Path.of(uploadDir);
        this.launcher = launcher;
        this.batches = batches;
        this.rowErrors = rowErrors;
        this.mappingService = mappingService;
        this.scope = scope;
    }

    /**
     * Stage the upload and launch the import job. Squad-bound principals may
     * only ingest into their own squad; the squad claim wins over any
     * client-supplied value (C3).
     */
    public UUID uploadAndLaunch(MultipartFile file, String profileName, UUID requestedSquadId,
                                String actorEmail, String actorRole) {
        UUID restriction = scope.restriction();
        UUID squadId = restriction != null ? restriction : requestedSquadId;
        if (squadId == null) {
            throw new IllegalArgumentException("squadId is required");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("empty upload");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safe = original.replaceAll("[^A-Za-z0-9._-]", "_");
        Path staged;
        try {
            Files.createDirectories(uploadDir);
            staged = uploadDir.resolve(UUID.randomUUID() + "-" + safe);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, staged);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not stage upload: " + e.getMessage(), e);
        }
        EntityType type = launcher.profileEntityType(profileName); // validates profile existence
        return launcher.launchAsync(staged, profileName, squadId, actorEmail, actorRole);
    }

    @Transactional(readOnly = true)
    public Page<IngestionBatch> batches(Pageable pageable) {
        return batches.findAllByOrderByStartedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public IngestionBatch batch(UUID id) {
        return batches.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("batch " + id));
    }

    @Transactional(readOnly = true)
    public List<IngestionRowError> rowErrors(UUID batchId) {
        return rowErrors.findByBatchIdOrderByRowNumber(batchId);
    }

    public List<ColumnMappingProfile> profiles() {
        return mappingService.listProfiles();
    }

    @Transactional
    public ColumnMappingProfile upsertProfile(String name, EntityType entityType,
                                              String sourceSystem, String mappingsJson) {
        return mappingService.upsertProfile(name, entityType, sourceSystem, mappingsJson);
    }
}
