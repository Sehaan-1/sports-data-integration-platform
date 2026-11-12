package com.sdip.api;

import com.sdip.security.AuthService;
import com.sdip.security.AuthService.TokenPair;
import com.sdip.security.SdipPrincipal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    @PostMapping("/login")
    public TokenPair login(@Validated @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Validated @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        SdipPrincipal p = CurrentUser.get();
        return Map.of(
                "userId", p.userId().toString(),
                "email", p.email(),
                "role", p.role().name(),
                "squadId", p.squadId() != null ? p.squadId().toString() : "");
    }
}
