package com.duozhi.outbound;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ControlApplication {
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--healthcheck")){
            int port=Integer.parseInt(System.getenv().getOrDefault("PORT","18000"));if(port<1||port>65535)throw new IllegalArgumentException("invalid healthcheck port");
            HttpURLConnection check=(HttpURLConnection)new URI("http",null,"127.0.0.1",port,"/readyz",null,null).toURL().openConnection(Proxy.NO_PROXY);
            check.setInstanceFollowRedirects(false);
            check.setConnectTimeout(3000);check.setReadTimeout(10000);
            try{if(check.getResponseCode()!=200)System.exit(1);}finally{check.disconnect();}return;
        }
        if(args.length==1&&args[0].startsWith("--database-migration=")) {
            DatabaseMigration.main(new String[]{args[0].substring("--database-migration=".length())});return;
        }
        SpringApplication.run(ControlApplication.class, args);
    }
}
