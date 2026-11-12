package com.sdip.security;

import com.sdip.domain.entity.AppUser;
import com.sdip.domain.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT issuance/validation (ADR-06): short-lived access tokens with
 * refresh rotation. Signing secret arrives via environment
 * ({@code SDIP_JWT_SECRET_B64}, base64, ≥32 bytes).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Development-only default (≈"sdip-dev-secret-change-me-32bytes!"). NEVER for production. */
    public static final String DEFAULT_DEV_SECRET_B64 =
            Base64.getEncoder().encodeToString("sdip-dev-secret-change-me-32bytes!".getBytes());

    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TTL = Duration.ofHours(12);

    private final SecretKey key;

    public JwtService(@Value("${sdip.security.jwt-secret-b64:}") String secretB64) {
        String effective = (secretB64 == null || secretB64.isBlank()) ? DEFAULT_DEV_SECRET_B64 : secretB64;
        if (DEFAULT_DEV_SECRET_B64.equals(effective)) {
            log.warn("Using the built-in DEVELOPMENT JWT secret. Set SDIP_JWT_SECRET_B64 for any real deployment.");
        }
        byte[] bytes = Decoders.BASE64.decode(effective);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes after base64 decode");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issueAccessToken(AppUser user) {
        UUID squadId = user.getSquad() != null ? user.getSquad().getId() : null;
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.getEmail())
                .id(UUID.randomUUID().toString())
                .claim("uid", user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TTL)))
                .signWith(key);
        if (squadId != null) {
            builder.claim("squad", squadId.toString());
        }
        return builder.compact();
    }

    public String issueRefreshToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .id(UUID.randomUUID().toString())
                .claim("uid", user.getId().toString())
                .claim("typ", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(REFRESH_TTL)))
                .signWith(key)
                .compact();
    }

    /** Parse + verify; throws {@link JwtException} on invalid/expired tokens. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public SdipPrincipal toPrincipal(Claims claims) {
        Role role = Role.valueOf(claims.get("role", String.class));
        String squad = claims.get("squad", String.class);
        return new SdipPrincipal(
                UUID.fromString(claims.get("uid", String.class)),
                claims.getSubject(),
                role,
                squad != null ? UUID.fromString(squad) : null);
    }

    public boolean isAccessToken(Claims claims) {
        return "access".equals(claims.get("typ", String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return "refresh".equals(claims.get("typ", String.class));
    }
}
