package com.sdip.api;

import com.sdip.domain.entity.ColumnMappingProfile;
import com.sdip.domain.entity.EntityType;
import com.sdip.domain.entity.IngestionBatch;
import com.sdip.domain.entity.IngestionRowError;
import com.sdip.security.SdipPrincipal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionAdminService service;

    public IngestionController(IngestionAdminService service) {
        this.service = service;
    }

    /** CSV/XLSX bulk upload (§4.2). Staff roles only; analyst is read-only. */
    @PostMapping("/files")
    @PreAuthorize("hasAnyRole('ADMIN','HEAD_COACH','PRACTITIONER')")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("profile") String profile,
                                                      @RequestParam(value = "squadId", required = false) UUID squadId) {
        SdipPrincipal p = CurrentUser.get();
        UUID batchId = service.uploadAndLaunch(file, profile, squadId, p.email(), p.role().name());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("batchId", batchId.toString(), "status", "STARTED",
                        "message", "ingestion launched; poll /api/ingest/batches/" + batchId));
    }

    @GetMapping("/batches")
    @PreAuthorize("isAuthenticated()")
    public Page<IngestionBatch> batches(Pageable pageable) {
        return service.batches(pageable);
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("isAuthenticated()")
    public IngestionBatch batch(@PathVariable UUID id) {
        return service.batch(id);
    }

    @GetMapping("/batches/{id}/errors")
    @PreAuthorize("hasAnyRole('ADMIN','HEAD_COACH','PRACTITIONER')")
    public List<IngestionRowError> rowErrors(@PathVariable UUID id) {
        return service.rowErrors(id);
    }

    @GetMapping("/mapping-profiles")
    @PreAuthorize("isAuthenticated()")
    public List<ColumnMappingProfile> profiles() {
        return service.profiles();
    }

    public record ProfileRequest(@NotBlank String name,
                                 @NotBlank String entityType,
                                 @NotBlank String sourceSystem,
                                 @NotBlank String mappings) {
    }

    @PostMapping("/mapping-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public ColumnMappingProfile upsertProfile(@Validated @RequestBody ProfileRequest request) {
        return service.upsertProfile(request.name(),
                EntityType.valueOf(request.entityType()),
                request.sourceSystem(), request.mappings());
    }
}
