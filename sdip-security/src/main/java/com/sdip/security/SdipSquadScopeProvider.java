package com.sdip.security;

import com.sdip.domain.service.SquadScopeProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges the security context to the core-module row scoping SPI: squad-bound
 * roles are restricted to their own squad; ADMIN and background/system work
 * (connectors, batch jobs — no authentication) see everything, which is safe
 * because those flows never serve a user request.
 */
@Component
@Primary
public class SdipSquadScopeProvider implements SquadScopeProvider {

    @Override
    public UUID currentSquadRestriction() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SdipPrincipal principal) {
            return principal.squadRestriction();
        }
        return null;
    }
}
