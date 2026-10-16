package com.duozhi.outbound;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.Map;

@Component
public class Bootstrap implements ApplicationRunner {
    private final Config config; private final Store store; private final Crypto crypto; private final DataSource source; private final CallbackInbox inbox;
    public Bootstrap(Config config,Store store,Crypto crypto,DataSource source,CallbackInbox inbox) { this.config=config;this.store=store;this.crypto=crypto;this.source=source;this.inbox=inbox; }
    @Override public void run(ApplicationArguments args) {
        ProductionConfiguration.validate(config);
        if(config.get("JWT_SECRET", "").length()<32) throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
        if(!"HS256".equals(config.get("JWT_ALGORITHM","HS256"))) throw new IllegalStateException("Only HS256 compatibility is implemented");
        if(config.flag("JAVA_INITIALIZE_SCHEMA",false)) {
            String dialect=config.get("spring.datasource.url","").startsWith("jdbc:postgresql:") ? "postgresql" : "sqlite";
            new ResourceDatabasePopulator(new ClassPathResource("contracts/schema-"+dialect+".sql")).execute(source);
        }
        // Existing schemas are read as-is. Runtime startup never alters existing tables.
        try(java.sql.Connection connection=source.getConnection()) {new DatabaseMigration(connection).verify();}
        catch(Exception incompatible){throw new IllegalStateException("Database compatibility check failed; run the explicit database migration plan before starting",incompatible);}
        inbox.initialize();
        if(config.flag("DEMO_USERS_ENABLED",false)) {
            int tenant=config.number("DEMO_TENANT_ID",1);
            if(store.one("tenant","WHERE id=?",tenant)==null) store.insert("tenant",Values.map("id",tenant,"name","Java development fixture","code","java-dev-"+tenant));
            seed(tenant,config.get("DEMO_ADMIN_USERNAME","admin"),config.get("DEMO_ADMIN_PASSWORD","12345678"),"admin","演示管理员");
            seed(tenant,config.get("DEMO_AGENT_USERNAME","1001@test"),config.get("DEMO_AGENT_PASSWORD","12345678"),"agent","演示座席 1001");
        }
    }
    private void seed(int tenant,String username,String password,String role,String name) {
        if(store.one("user","WHERE username=?",username)!=null) return;
        store.insert("user",Values.map("tenant_id",tenant,"username",username,"password_hash",crypto.password(password),"full_name",name,"role",role,"is_supervisor",role.equals("admin")));
    }
}
