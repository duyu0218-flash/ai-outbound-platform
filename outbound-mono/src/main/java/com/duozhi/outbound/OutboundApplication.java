package com.duozhi.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OutboundApplication {
    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("OUTBOUND_RUNTIME_ROLE", "control");
        if (role.equals("media")) {
            com.duozhi.mediaworker.MediaWorkerApplication.run(args);
            return;
        }
        if (!role.equals("control")) throw new IllegalArgumentException("invalid runtime role");
        SpringApplication.run(OutboundApplication.class, args);
    }
}
