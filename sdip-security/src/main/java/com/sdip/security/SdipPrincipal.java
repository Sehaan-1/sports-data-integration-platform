package com.sdip.security;

import com.sdip.domain.entity.Role;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Authenticated identity carried through the security context. */
public record SdipPrincipal(UUID userId, String email, Role role, UUID squadId)
        implements AuthenticatedPrincipal {

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getName() {
        return email;
    }

    /** Squad restriction for row-level scoping; null for ADMIN (platform-wide). */
    public UUID squadRestriction() {
        return role == Role.ADMIN ? null : squadId;
    }
}
