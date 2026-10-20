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
    DruidDataSource dataSource(DataSourceProperties properties){
        var pool=properties.initializeDataSourceBuilder().type(DruidDataSource.class).build();
        // Connector/J tracks session flags from server responses; avoid a network query
        // for each isReadOnly/getAutoCommit check. Commit/fsync and isolation are unchanged.
        pool.addConnectionProperty("useLocalSessionState","true");return pool;
    }
    @Bean(name="voiceLedgerDataSource",destroyMethod="close")
    DruidDataSource voiceLedgerDataSource(DataSourceProperties properties){
        var pool=properties.initializeDataSourceBuilder().type(DruidDataSource.class).build();pool.setName("voiceLedgerDataSource");pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(4);pool.setMaxWait(5000);pool.setDefaultTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return pool;
    }
}
