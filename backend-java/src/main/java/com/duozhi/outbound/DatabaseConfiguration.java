package com.duozhi.outbound;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {
    @Bean
    HikariDataSource dataSource(DataSourceProperties properties,Config config){
        com.zaxxer.hikari.HikariDataSource source=properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setMaximumPoolSize(config.number("spring.datasource.hikari.maximum-pool-size",5));source.setConnectionTimeout(5000);
        if(properties.getUrl().startsWith("jdbc:sqlite:")){
            // Local SQLite cannot provide PostgreSQL row locking. A single pooled
            // connection serializes transactions, including shared-memory fixtures.
            source.setMaximumPoolSize(1);
            source.setConnectionInitSql("PRAGMA foreign_keys=ON");source.addDataSourceProperty("busy_timeout","5000");
        }else if(properties.getUrl().startsWith("jdbc:postgresql:")){
            source.addDataSourceProperty("stringtype","unspecified");
        }else throw new IllegalArgumentException("Only PostgreSQL and SQLite compatibility is implemented");
        return source;
    }
}
