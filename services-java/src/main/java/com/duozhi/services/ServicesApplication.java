package com.duozhi.services;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ServicesApplication {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--healthcheck")) {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8003"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
            var connection = (HttpURLConnection) new URI("http", null, "127.0.0.1", port, "/readyz", null, null).toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
            String token = new Settings().secret("media".equals(System.getenv("JAVA_SERVICE_ROLE")) ? "MEDIA_RPC_TOKEN" : "SERVICE_TOKEN");
            if (token != null && !token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token);
            try { if (connection.getResponseCode() != 200) System.exit(1); }
            finally { connection.disconnect(); }
            return;
        }
        String role = System.getenv().getOrDefault("JAVA_SERVICE_ROLE", "recording");
        if (!Set.of("recording", "agent", "voice", "media").contains(role)) throw new IllegalArgumentException("invalid Java service role");
        if (role.equals("agent")) {
            Settings settings = new Settings();
            long connections = settings.number("LLM_MAX_CONNECTIONS", 256, 1, 1024);
            long idle = settings.number("LLM_MAX_KEEPALIVE_CONNECTIONS", Math.min(128, connections), 1, connections);
            // This JDK setting is read when the first HTTP client is constructed.
            System.setProperty("jdk.httpclient.connectionPoolSize", Long.toString(idle));
        }
        SpringApplication.run(ServicesApplication.class, args);
    }
}
