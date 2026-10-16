package com.duozhi.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class ControlApplication {
    public static void main(String[] args) { SpringApplication.run(ControlApplication.class, args); }
}
