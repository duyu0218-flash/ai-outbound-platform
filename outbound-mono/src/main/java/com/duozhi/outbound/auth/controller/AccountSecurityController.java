package com.duozhi.outbound.auth.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.AccountSecurity;
import com.duozhi.outbound.auth.service.Crypto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountSecurityController {
    private final AccountSecurity security;
    private final Crypto crypto;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.PhoneVerification phoneVerification;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AuthService auth;

    public AccountSecurityController(AccountSecurity security, Crypto crypto) {
        this.security = security;
        this.crypto = crypto;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        return i;
    }

    @GetMapping("/security")
    public Object get(HttpServletRequest r) {
        return security.read(actor(r));
    }

    @PostMapping("/security/phone/send")
    public Object sendPhone(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        return phoneVerification.send(i, i.userId(), b);
    }

    @PostMapping("/security/phone/confirm")
    public Object confirmPhone(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        return phoneVerification.confirm(i, i.userId(), b);
    }

    @PutMapping("/profile")
    public Object profile(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        security.verifyChange(i, b);
        String name =
                com.duozhi.outbound.workspace.service.WorkspaceDocuments.text(
                        b, "username", 160, true);
        if (!name.matches("[\\p{L}0-9_.@-]{1,160}"))
            throw new com.duozhi.outbound.common.exception.ApiError(422, "登录名格式无效");
        boolean changed = !name.equals(i.user().get("username"));
        if (changed
                && Boolean.TRUE.equals(
                        security.settings(i.tenant(), i.userId()).get("password_disabled")))
            throw new com.duozhi.outbound.common.exception.ApiError(422, "请先开启密码登录后修改登录名");
        try {
            auth.patch(
                    ((Number) i.userId()).longValue(),
                    (int) i.tenant(),
                    Map.of(
                            "username",
                            name,
                            "token_version",
                            ((Number) i.user().get("token_version")).intValue()
                                    + (changed ? 1 : 0)));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new com.duozhi.outbound.common.exception.ApiError(409, "登录名已存在");
        }
        return Map.of("login_required", changed);
    }

    @PutMapping("/security")
    public Object save(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return security.save(actor(r), b, r.getRemoteAddr());
    }

    @PostMapping("/security/totp")
    public Object enroll(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return security.enroll(actor(r), b);
    }

    @PostMapping("/security/totp/confirm")
    public Object confirm(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return security.confirm(actor(r), b);
    }

    @PostMapping("/security/totp/disable")
    public Object disable(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return security.disableTotp(actor(r), b);
    }

    @PostMapping("/security/key")
    public Object key(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return security.key(actor(r), b);
    }

    @PostMapping("/key-session")
    public Object session(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        if (security.settings(i.tenant(), i.userId()).containsKey("totp_secret"))
            security.requireOtp(i, Objects.toString(b.get("code"), ""));
        return Map.of(
                "access_token",
                crypto.token(i.user()),
                "role",
                i.user().get("role"),
                "token_type",
                "bearer");
    }
}
