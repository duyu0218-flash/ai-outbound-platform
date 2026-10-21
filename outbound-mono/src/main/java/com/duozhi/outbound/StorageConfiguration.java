package com.duozhi.outbound;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import java.sql.Connection;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class StorageConfiguration {
    @Bean @Primary @ConfigurationProperties("spring.datasource.druid")
    DruidDataSource dataSource(DataSourceProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("realtimeDataSource") DruidDataSource realtime,
            @org.springframework.beans.factory.annotation.Qualifier("analysisDataSource") DruidDataSource analysis){
        var pool=new WorkloadDataSource(realtime,analysis);pool.setUrl(properties.determineUrl());pool.setUsername(properties.determineUsername());pool.setPassword(properties.determinePassword());
        // Connector/J tracks session flags from server responses; avoid a network query
        // for each isReadOnly/getAutoCommit check. Commit/fsync and isolation are unchanged.
        configureDriver(pool);return pool;
    }
    @Bean(name="realtimeDataSource",destroyMethod="close")
    DruidDataSource realtimeDataSource(DataSourceProperties properties,Config config){return lanePool(properties,config,"realtimeDataSource","JAVA_REALTIME_DB_POOL",4,8);}
    @Bean(name="analysisDataSource",destroyMethod="close")
    DruidDataSource analysisDataSource(DataSourceProperties properties,Config config){return lanePool(properties,config,"analysisDataSource","JAVA_ANALYSIS_DB_POOL",1,2);}
    private DruidDataSource lanePool(DataSourceProperties properties,Config config,String name,String key,int fallback,int maximum){
        int size=config.number(key,fallback);if(size<1||size>maximum)throw new IllegalArgumentException("invalid bounded "+key);
        var pool=properties.initializeDataSourceBuilder().type(DruidDataSource.class).build();pool.setName(name);pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(size);pool.setMaxWait(5000);pool.setDefaultTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        pool.setConnectionInitSqls(java.util.List.of("SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',NO_AUTO_VALUE_ON_ZERO')"));configureDriver(pool);return pool;
    }
    @Bean(name="voiceLedgerDataSource",destroyMethod="close")
    DruidDataSource voiceLedgerDataSource(DataSourceProperties properties){
        var pool=properties.initializeDataSourceBuilder().type(DruidDataSource.class).build();pool.setName("voiceLedgerDataSource");pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(4);pool.setMaxWait(5000);pool.setDefaultTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);configureDriver(pool);return pool;
    }
    @Bean(name="capacityEvidenceDataSource",destroyMethod="close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="JAVA_CAPACITY_EVIDENCE_ENABLED",havingValue="true")
    DruidDataSource capacityEvidenceDataSource(DataSourceProperties properties){
        var pool=properties.initializeDataSourceBuilder().type(DruidDataSource.class).build();pool.setName("capacityEvidenceDataSource");pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(2);pool.setMaxWait(5000);configureDriver(pool);return pool;
    }
    private static void configureDriver(DruidDataSource pool){
        pool.addConnectionProperty("useLocalSessionState","true");pool.addConnectionProperty("rewriteBatchedStatements","true");
        // Bounded per-connection cache for the fixed MyBatis statements. Reuse
        // server parsing and binary parameters without changing TLS or durability.
        pool.addConnectionProperty("useServerPrepStmts","true");pool.addConnectionProperty("cachePrepStmts","true");
        pool.addConnectionProperty("prepStmtCacheSize","128");pool.addConnectionProperty("prepStmtCacheSqlLimit","65536");
    }
}
