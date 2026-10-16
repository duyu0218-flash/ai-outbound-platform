package com.duozhi.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class ControlApplication {
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--healthcheck")){
            java.net.HttpURLConnection check=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+System.getenv().getOrDefault("PORT","18000")+"/readyz").openConnection();
            check.setConnectTimeout(3000);check.setReadTimeout(10000);
            try{if(check.getResponseCode()!=200)System.exit(1);}finally{check.disconnect();}return;
        }
        if(args.length==1&&args[0].startsWith("--database-migration=")) {
            DatabaseMigration.main(new String[]{args[0].substring("--database-migration=".length())});return;
        }
        SpringApplication.run(ControlApplication.class, args);
    }
}
