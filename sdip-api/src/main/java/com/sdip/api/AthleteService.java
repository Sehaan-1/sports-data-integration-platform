package com.sdip.api;

import com.sdip.domain.entity.Athlete;
import com.sdip.domain.repo.AthleteRepository;
import com.sdip.domain.service.RowScopeGuard;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Scope-aware athlete reads (RQ3): list queries rely on the Hibernate
 * squadFilter enabled inside this transaction; direct by-ID lookups get an
 * explicit squad check because Hibernate filters do not apply to
 * identifier-based loads (defense in depth).
 */
@Service
public class AthleteService {

    public record AthleteDto(String id, String fullName, LocalDate dob, String email,
                             String gender, boolean provisional, String squadId, String squadName) {
    }

    private final AthleteRepository athletes;
    private final RowScopeGuard scope;

    public AthleteService(AthleteRepository athletes, RowScopeGuard scope) {
        this.athletes = athletes;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public Page<AthleteDto> list(Pageable pageable) {
        scope.enableForCurrentPrincipal();
        return athletes.findAll(pageable).map(AthleteService::toDto);
    }

    @Transactional(readOnly = true)
    public AthleteDto get(UUID id) {
        scope.enableForCurrentPrincipal();
        Athlete athlete = athletes.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("athlete " + id));
        UUID restriction = scope.restriction();
        if (restriction != null && !restriction.equals(athlete.getSquad().getId())) {
            throw new EntityNotFoundException("athlete " + id); // cross-tenant → 404, no existence leak
        }
        return toDto(athlete);
    }

    @Transactional(readOnly = true)
    public Athlete requireScopedEntity(UUID id) {
        scope.enableForCurrentPrincipal();
        Athlete athlete = athletes.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("athlete " + id));
        UUID restriction = scope.restriction();
        if (restriction != null && !restriction.equals(athlete.getSquad().getId())) {
            throw new EntityNotFoundException("athlete " + id);
        }
        return athlete;
    }

    static AthleteDto toDto(Athlete a) {
        return new AthleteDto(a.getId().toString(), a.getFullName(), a.getDob(), a.getEmail(),
                a.getGender(), a.isProvisional(),
                a.getSquad().getId().toString(), a.getSquad().getName());
    }
}
