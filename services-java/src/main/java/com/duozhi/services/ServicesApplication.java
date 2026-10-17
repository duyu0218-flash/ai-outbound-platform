package com.duozhi.services;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ServicesApplication {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--healthcheck")) {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8003"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
            var connection = (java.net.HttpURLConnection) new java.net.URI("http", null, "127.0.0.1", port, "/readyz", null, null).toURL().openConnection(java.net.Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
            String token = new Settings().secret("media".equals(System.getenv("JAVA_SERVICE_ROLE")) ? "MEDIA_RPC_TOKEN" : "SERVICE_TOKEN");
            if (token != null && !token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token);
            try { if (connection.getResponseCode() != 200) System.exit(1); }
            finally { connection.disconnect(); }
            return;
        }
        String role = System.getenv().getOrDefault("JAVA_SERVICE_ROLE", "recording");
        if (!java.util.Set.of("recording", "agent", "voice", "media").contains(role)) throw new IllegalArgumentException("invalid Java service role");
        SpringApplication.run(ServicesApplication.class, args);
    }
}
