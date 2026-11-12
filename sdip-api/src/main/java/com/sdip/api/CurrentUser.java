package com.sdip.api;

import com.sdip.security.SdipPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

/** Resolve the authenticated principal (401-safe helper for controllers). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static SdipPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SdipPrincipal principal) {
            return principal;
        }
        throw new AccessDeniedException("no authenticated principal");
    }
}
