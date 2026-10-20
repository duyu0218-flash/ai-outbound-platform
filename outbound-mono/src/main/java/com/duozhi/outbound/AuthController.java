package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service; private final Crypto crypto;  private final Config config;
    public AuthController(AuthService service,Crypto crypto,Config config) { this.service=service;this.crypto=crypto;this.config=config; }
    public static class Login {
        @NotNull @Size(min=1,max=200) public String username;
        @NotNull @Size(min=1,max=1024) public String password;
        public String username(){return username;}
        public String password(){return password;}
    }
    public static class Presence {
        @NotNull @Pattern(regexp="ready|busy|offline") public String status;
        public String status(){return status;}
    }
    @PostMapping("/login")
    public Map<String,Object> login(@Valid @RequestBody Login body) {
        java.util.Map<java.lang.String,java.lang.Object> user=service.login(body.username(),body.password());
        if(user==null) throw new ApiError(401,"invalid username or password");
        return Values.map("access_token",crypto.token(user),"token_type","bearer","username",user.get("username"),"role",user.get("role"),"tenant_id",user.get("tenant_id"));
    }
    @GetMapping("/me")
    public Map<String,Object> me(HttpServletRequest request) { return AuthService.publicUser(Identity.from(request).requireUser()); }
    @PostMapping("/logout")
    public Map<String,String> logout(HttpServletRequest request) { service.logout(Identity.from(request));return Values.map("result","ok"); }
    @PutMapping("/presence")
    public Map<String,Object> presence(@Valid @RequestBody Presence body,HttpServletRequest request) {
        com.duozhi.outbound.Identity identity=Identity.from(request);java.util.Map<java.lang.String,java.lang.Object> user=identity.requireUser();
        if(!"agent".equals(user.get("role"))) throw new ApiError(409,"presence is only available for agents");
        if(body.status().equals("ready") && config.flag("WEBRTC_ENABLED",false)) throw new ApiError(409,"SIP registration and microphone readiness are required before accepting calls");
        return service.presence(identity,body.status());
    }
}
