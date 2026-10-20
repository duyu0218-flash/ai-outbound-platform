package com.duozhi.outbound;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Config {
    private final Environment environment;
    public Config(Environment environment) { this.environment = environment; }
    public String get(String name, String fallback) { return environment.getProperty(name, fallback); }
    public boolean flag(String name, boolean fallback) { return Boolean.parseBoolean(get(name, "" + fallback)); }
    public int number(String name, int fallback) { return Integer.parseInt(get(name, "" + fallback)); }
    public boolean production() { return Values.set("prod", "production").contains(get("ENV", "dev").transform(Ascii::lower)); }
}
