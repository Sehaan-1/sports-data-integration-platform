package com.sdip.api;

import com.sdip.domain.entity.AppUser;
import com.sdip.domain.entity.AuditEvent;
import com.sdip.domain.entity.Role;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AppUserRepository;
import com.sdip.domain.repo.AuditEventRepository;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.domain.service.AuditService;
import com.sdip.security.SdipPrincipal;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Platform administration: user provisioning and audit-log inspection. ADMIN only. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AppUserRepository users;
    private final SquadRepository squads;
    private final AuditEventRepository auditEvents;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public AdminController(AppUserRepository users, SquadRepository squads,
                           AuditEventRepository auditEvents, PasswordEncoder passwords,
                           AuditService audit) {
        this.users = users;
        this.squads = squads;
        this.auditEvents = auditEvents;
        this.passwords = passwords;
        this.audit = audit;
    }

    public record CreateUserRequest(@Email @NotBlank String email,
                                    @NotBlank @Size(min = 8, max = 72) String password,
                                    @NotBlank String displayName,
                                    @NotNull Role role,
                                    UUID squadId) {
    }

    @PostMapping("/users")
    @Transactional
    public Map<String, Object> createUser(@Validated @RequestBody CreateUserRequest request) {
        users.findByEmailIgnoreCase(request.email()).ifPresent(u -> {
            throw new IllegalStateException("email already registered: " + request.email());
        });
        Squad squad = null;
        if (request.role() != Role.ADMIN) {
            if (request.squadId() == null) {
                throw new IllegalArgumentException("squadId is required for squad-scoped roles");
            }
            squad = squads.findById(request.squadId())
                    .orElseThrow(() -> new EntityNotFoundException("squad " + request.squadId()));
        }
        AppUser user = new AppUser(request.email(), passwords.encode(request.password()),
                request.displayName(), request.role(), squad);
        users.save(user);
        SdipPrincipal p = CurrentUser.get();
        audit.record(p.email(), p.role().name(), "PERMISSION_CHANGE", "AppUser", user.getId().toString(),
                Map.of("action", "user-created", "newRole", user.getRole().name(),
                        "squadId", squad != null ? squad.getId().toString() : ""));
        return Map.of("id", user.getId().toString(), "email", user.getEmail(),
                "role", user.getRole().name());
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public Page<AppUser> users(Pageable pageable) {
        return users.findAll(pageable);
    }

    /** Immutable audit log inspection (append-only; no mutation endpoints exist by design). */
    @GetMapping("/audit")
    @Transactional(readOnly = true)
    public Page<AuditEvent> audit(Pageable pageable) {
        return auditEvents.findAllByOrderByCreatedAtDesc(pageable);
    }

    @GetMapping("/squads")
    @Transactional(readOnly = true)
    public java.util.List<Squad> squads() {
        return squads.findAll();
    }
}
