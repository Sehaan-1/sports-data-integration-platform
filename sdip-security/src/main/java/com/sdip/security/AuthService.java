package com.sdip.security;

import com.sdip.domain.entity.AppUser;
import com.sdip.domain.repo.AppUserRepository;
import com.sdip.domain.service.AuditService;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Credential verification, token issuance, and refresh rotation (ADR-06). */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(AppUserRepository users, PasswordEncoder passwords,
                       JwtService jwtService, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    public record TokenPair(String accessToken, String refreshToken, String tokenType,
                            long expiresInSeconds, SdipPrincipal principal) {
    }

    @Transactional
    public TokenPair login(String email, String password) {
        AppUser user = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .filter(AppUser::isEnabled)
                .orElse(null);
        if (user == null || !passwords.matches(password != null ? password : "", user.getPasswordHash())) {
            audit.record(email != null ? email : "unknown", "ANONYMOUS", "LOGIN_FAILED",
                    "AppUser", null, Map.of("reason", "bad credentials"));
            throw new BadCredentialsException("invalid email or password");
        }
        audit.record(user.getEmail(), user.getRole().name(), "LOGIN", "AppUser", user.getId().toString(), null);
        return tokensFor(user);
    }

    @Transactional
    public TokenPair refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (Exception e) {
            throw new BadCredentialsException("invalid refresh token");
        }
        if (!jwtService.isRefreshToken(claims)) {
            throw new BadCredentialsException("not a refresh token");
        }
        AppUser user = users.findByEmailIgnoreCase(claims.getSubject())
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("user no longer active"));
        // rotation: a brand-new pair is issued; callers must discard the old refresh token
        audit.record(user.getEmail(), user.getRole().name(), "TOKEN_REFRESH",
                "AppUser", user.getId().toString(), null);
        return tokensFor(user);
    }

    private TokenPair tokensFor(AppUser user) {
        String access = jwtService.issueAccessToken(user);
        String refresh = jwtService.issueRefreshToken(user);
        UUID squad = user.getSquad() != null ? user.getSquad().getId() : null;
        return new TokenPair(access, refresh, "Bearer",
                JwtService.ACCESS_TTL.toSeconds(),
                new SdipPrincipal(user.getId(), user.getEmail(), user.getRole(), squad));
    }
}
