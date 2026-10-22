package com.duozhi.outbound.auth.service;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.common.config.Config;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

@Component
public class AuthDemoBootstrap implements ApplicationRunner {
    private final Config config;
    private final TenantMapper tenants;
    private final SysUserMapper users;
    private final AuthIdentityMapper identities;
    private final Crypto crypto;

    public AuthDemoBootstrap(
            Config config,
            TenantMapper tenants,
            SysUserMapper users,
            AuthIdentityMapper identities,
            Crypto crypto) {
        this.config = config;
        this.tenants = tenants;
        this.users = users;
        this.identities = identities;
        this.crypto = crypto;
    }

    public void run(ApplicationArguments args) {
        if (!config.flag("DEMO_USERS_ENABLED", false)) return;
        int tenant = config.number("DEMO_TENANT_ID", 1);
        if (tenants.findById(tenant) == null) {
            Tenant row = new Tenant();
            row.setId(tenant);
            row.setName("Java development fixture");
            row.setCode("java-dev-" + tenant);
            tenants.insert(row);
        }
        seed(
                tenant,
                config.get("DEMO_ADMIN_USERNAME", "admin"),
                config.get("DEMO_ADMIN_PASSWORD", "12345678"),
                "admin",
                "演示管理员");
        seed(
                tenant,
                config.get("DEMO_AGENT_USERNAME", "1001@test"),
                config.get("DEMO_AGENT_PASSWORD", "12345678"),
                "agent",
                "演示座席 1001");
    }

    private void seed(int tenant, String username, String password, String role, String name) {
        if (identities.findByUsernameForLogin(username) != null) return;
        SysUser user = new SysUser();
        user.setTenantId(tenant);
        user.setUsername(username);
        user.setPasswordHash(crypto.password(password));
        user.setFullName(name);
        user.setRole(role);
        user.setIsSupervisor(role.equals("admin"));
        users.insert(user);
    }
}
