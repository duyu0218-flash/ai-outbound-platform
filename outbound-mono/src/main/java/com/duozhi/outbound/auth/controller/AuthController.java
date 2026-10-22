package com.duozhi.outbound.auth.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.AuthService;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.operations.controller.OperationsWorkforce;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final Crypto crypto;
    private final Config config;
    private final OperationsWorkforce workforce;

    public AuthController(
            AuthService service, Crypto crypto, Config config, OperationsWorkforce workforce) {
        this.service = service;
        this.crypto = crypto;
        this.config = config;
        this.workforce = workforce;
    }

    public static class Login {
        @NotNull
        @Size(min = 1, max = 200)
        public String username;

        @NotNull
        @Size(min = 1, max = 1024)
        public String password;

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }
    }

    public static class Presence {
        @NotNull
        @Pattern(regexp = "ready|busy|offline")
        public String status;

        public String status() {
            return status;
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid
    @RequestBody
    Login body) {
        Map<String, Object> user = service.login(body.username(), body.password());
        if (user == null) throw new ApiError(401, "invalid username or password");
        return Values.map(
                "access_token",
                crypto.token(user),
                "token_type",
                "bearer",
                "username",
                user.get("username"),
                "role",
                user.get("role"),
                "tenant_id",
                user.get("tenant_id"));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return AuthService.publicUser(Identity.from(request).requireUser());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        service.logout(Identity.from(request));
        return Values.map("result", "ok");
    }

    @PutMapping("/presence")
    public Map<String, Object> presence(
            @Valid
            @RequestBody
            Presence body, HttpServletRequest request) {
        Identity identity = Identity.from(request);
        Map<String, Object> user = identity.requireUser();
        if (!"agent".equals(user.get("role")))
            throw new ApiError(409, "presence is only available for agents");
        if (body.status().equals("ready")) workforce.ready(identity);
        return service.presence(identity, body.status());
    }
}
