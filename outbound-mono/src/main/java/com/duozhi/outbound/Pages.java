package com.duozhi.outbound;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import java.nio.file.Path;

@Controller
public class Pages {
    private final Config config;
    public Pages(Config config){this.config=config;}
    @GetMapping({"/admin","/admin/**","/agent","/agent/**"})
    public ResponseEntity<?> index(){
        org.springframework.core.io.ClassPathResource file=new org.springframework.core.io.ClassPathResource("static/index.html");
        if(!file.exists())return ResponseEntity.status(503).contentType(MediaType.TEXT_HTML).body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><h1>前端资源尚未构建</h1><p>请先构建 frontend。</p></html>");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(file);
    }
}
